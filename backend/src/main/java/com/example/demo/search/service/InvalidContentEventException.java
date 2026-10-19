package com.example.demo.search.service;

public class InvalidContentEventException extends RuntimeException {
    public InvalidContentEventException(String code) {
        super(code);
    }
}
