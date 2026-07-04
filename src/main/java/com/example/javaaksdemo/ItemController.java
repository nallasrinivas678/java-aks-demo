package com.example.javaaksdemo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@RestController
public class ItemController {

    private final AtomicLong idSequence = new AtomicLong();

    private final List<Item> items = List.of(
            new Item(idSequence.incrementAndGet(), "Widget"),
            new Item(idSequence.incrementAndGet(), "Gadget"),
            new Item(idSequence.incrementAndGet(), "Doohickey")
    );

    @GetMapping("/api/hello")
    public String hello() {
        return "Hello from java-aks-demo running on AKS!";
    }

    @GetMapping("/api/items")
    public List<Item> items() {
        return items;
    }
}
