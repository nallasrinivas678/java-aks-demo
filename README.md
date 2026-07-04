# CI/CD, Docker & AKS — How This Project Ships Code

This document explains how a code change goes from `git push` to running in
production on AKS, using this repo's actual files as the reference:
[.github/workflows/ci-cd.yml](.github/workflows/ci-cd.yml), [Dockerfile](Dockerfile),
and the manifests in [k8s/](k8s/).

## 1. What triggers the pipeline?

The workflow ([.github/workflows/ci-cd.yml](.github/workflows/ci-cd.yml)) runs on
every push to `main`:

```yaml
on:
  push:
    branches: [main]
```

It has two jobs, run in order:

1. `build-test` — compiles and tests the code.
2. `build-push-deploy` — builds a Docker image, pushes it to ACR, and deploys it
   to AKS. This job only runs if `build-test` succeeded, because of:
   ```yaml
   build-push-deploy:
     needs: build-test
   ```

### How does `build-push-deploy` know `build-test` succeeded?

It doesn't check anything itself — GitHub Actions' own orchestrator does. It reads
every job's `needs:` field, builds a dependency graph, and holds `build-push-deploy`
back until `build-test` reaches a final result. If `build-test` fails or is
cancelled, `build-push-deploy` is skipped automatically (no `if:` override is
needed — that's the default behavior for `needs`).

## 2. Each job runs on a brand-new, disposable VM

`runs-on: ubuntu-latest` means GitHub provisions a fresh Ubuntu VM for **each job**,
runs the steps, then destroys the VM completely once the job ends — nothing is
reused or left running.

- `build-test` and `build-push-deploy` get **two separate VMs**, even though one
  runs right after the other. That's why `build-push-deploy` has its own
  `actions/checkout@v4` step — the second VM has no memory of the first one and
  needs the source code checked out again.
- `cache: maven` doesn't keep a VM alive between runs; it saves the `~/.m2`
  dependency folder to GitHub's cache storage so a *future* fresh VM can restore
  it instead of re-downloading every dependency from Maven Central.

## 3. `build-test`: does the code even work?

```yaml
- uses: actions/checkout@v4
- uses: actions/setup-java@v4
  with:
    java-version: '17'
    distribution: 'temurin'
    cache: maven
- run: mvn -B verify
```

Checks out the code, installs JDK 17, then runs `mvn verify` (compiles + runs
tests). If this fails, the pipeline stops here — nothing gets built into an
image or shipped anywhere. This is the safety gate: a broken test never reaches
ACR or AKS.

## 4. `build-push-deploy`: build an image, ship it, deploy it

### 4a. Authenticate to Azure

```yaml
- uses: azure/login@v2
  with:
    client-id: ${{ secrets.AZURE_CLIENT_ID }}
    tenant-id: ${{ secrets.AZURE_TENANT_ID }}
    subscription-id: ${{ secrets.AZURE_SUBSCRIPTION_ID }}
```

Uses OIDC (a trusted handshake, no long-lived password stored in GitHub) to prove
to Azure that this workflow run is allowed to act on the subscription.

### 4b. Build and push the Docker image

```yaml
az acr login --name ${{ env.ACR_NAME }}
docker build -t ${{ env.ACR_LOGIN_SERVER }}/${{ env.IMAGE_NAME }}:${{ github.sha }} .
docker push ${{ env.ACR_LOGIN_SERVER }}/${{ env.IMAGE_NAME }}:${{ github.sha }}
```

**Important: this does not just publish a `.jar` file.** `docker build` reads the
[Dockerfile](Dockerfile), which is a two-stage recipe:

```dockerfile
# Stage 1: build the jar (this stage is discarded afterward)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# Stage 2: minimal runtime image that actually gets shipped
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- **Stage 1** uses a full Maven+JDK image to compile the code into a jar
  (`mvn package -DskipTests` — tests already ran in `build-test`, no need to
  repeat them). Copying `pom.xml` before `src` lets Docker reuse the downloaded
  dependencies layer from cache when only source code changes, not dependencies.
- **Stage 2** starts from a *different*, much smaller base image that only has a
  JRE (no compiler, no Maven, no build tools). It copies **only the finished jar**
  out of stage 1, renames it `app.jar`, creates a non-root `spring` user to run
  as (so a compromised app doesn't get root inside the container), and sets
  `ENTRYPOINT ["java", "-jar", "app.jar"]` as the command that runs the instant a
  container starts from this image.

The result of `docker build` is a single **image**: a self-contained, layered
filesystem with a Linux base, a JRE, `app.jar`, and instructions on how to start
it. `docker push` uploads that whole image (all its layers) to ACR, tagged
`java-aks-demo:<commit-sha>` — not a bare jar file. Tagging with the commit SHA
means every push produces a uniquely labeled image; nothing gets silently
overwritten.

### 4c. Point kubectl at the right cluster

```yaml
- uses: azure/aks-set-context@v4
  with:
    resource-group: ${{ env.RESOURCE_GROUP }}
    cluster-name: ${{ env.AKS_CLUSTER }}
```

### 4d. Deploy to AKS

```yaml
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
kubectl set image deployment/${{ env.IMAGE_NAME }} \
  ${{ env.IMAGE_NAME }}=${{ env.ACR_LOGIN_SERVER }}/${{ env.IMAGE_NAME }}:${{ github.sha }} \
  -n ${{ env.NAMESPACE }}
kubectl rollout status deployment/${{ env.IMAGE_NAME }} -n ${{ env.NAMESPACE }} --timeout=180s
```

- `kubectl apply -f ...` ensures the namespace/deployment/service objects exist
  and match the YAML in [k8s/](k8s/) (creates them the first time, updates them
  on later runs).
- `kubectl set image` tells the existing Deployment to switch its container image
  to this run's freshly pushed `<commit-sha>` tag — this is the actual "go live"
  step.
- `kubectl rollout status --timeout=180s` blocks the pipeline until Kubernetes
  confirms the new pods are healthy and up, and fails the job if that doesn't
  happen within 180 seconds.

## 5. How AKS runs the container

- [k8s/namespace.yaml](k8s/namespace.yaml) — an isolated `java-aks-demo`
  namespace so these resources don't collide with other apps on the same
  cluster.
- [k8s/deployment.yaml](k8s/deployment.yaml) — declares the desired state:
  - `replicas: 2` — always keep 2 running copies (pods) of the container.
  - `resources.requests/limits` — caps how much CPU/memory each pod can use.
  - `readinessProbe` (`/actuator/health/readiness`) — a pod isn't sent traffic
    until this passes.
  - `livenessProbe` (`/actuator/health/liveness`) — if this starts failing,
    Kubernetes kills and replaces that pod automatically.
- [k8s/service.yaml](k8s/service.yaml) — a `LoadBalancer` Service that gives
  the app one stable public address (port 80) and forwards traffic to whichever
  healthy pod (port 8080) is available, even as pods are replaced underneath it.

Kubernetes continuously compares "what you asked for" (2 healthy pods running
this image tag) against "what's actually running," and reconciles any
difference — replacing crashed pods, moving pods off failed nodes, and rolling
out new image versions one pod at a time so the Service always has at least one
healthy pod to route to (zero-downtime deploys).

## 6. End-to-end example: pushing a one-line code change

Say you fix a typo in a controller and run `git push origin main`. Here's the
full chain reaction:

1. GitHub sees the push, matches `on: push: branches: [main]`, and starts the
   workflow.
2. A fresh Ubuntu VM spins up for `build-test`: checks out your commit, installs
   JDK 17, runs `mvn -B verify`. Say it passes.
3. That VM is destroyed. A **new** fresh Ubuntu VM spins up for
   `build-push-deploy` (since `build-test` succeeded).
4. It checks out the code again, logs into Azure via OIDC, then runs
   `docker build` — which internally starts a temporary Maven container to
   compile your fixed code into a jar, then copies that jar into a slim
   JRE-only image tagged `...:a1b2c3d` (your commit SHA).
5. `docker push` uploads that image to ACR. It now sits in the registry,
   available to pull, but nothing in production has changed yet.
6. `aks-set-context` points `kubectl` at your AKS cluster.
7. `kubectl apply` makes sure the namespace/deployment/service objects exist and
   match the repo's YAML.
8. `kubectl set image` tells the Deployment: "use image tag `a1b2c3d` from now
   on." Kubernetes starts a **rolling update**: it launches a new pod with the
   new image, waits for its readiness probe to pass, then only *after* that
   terminates one of the old pods — repeating until both replicas run the new
   version. At no point does the Service go down, since it always has at least
   one ready pod to route to.
9. `kubectl rollout status --timeout=180s` waits for that rollout to finish and
   reports success or failure back to the GitHub Actions job.
10. The VM is destroyed. Your one-line fix is now live behind the LoadBalancer's
    public IP, running as 2 replicas on AKS.

If step 2 (`mvn -B verify`) had failed instead, the pipeline would have stopped
right there — `build-push-deploy` would never run, no image would be built, and
production would be untouched. That's the safety gate this pipeline is built
around.