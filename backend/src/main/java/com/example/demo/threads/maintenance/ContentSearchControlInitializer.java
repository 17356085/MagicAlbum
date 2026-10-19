package com.example.demo.threads.maintenance;

import com.example.demo.threads.maintenance.service.ContentWriteGateService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Initializes the singleton row after Hibernate creates an H2 create-drop schema. */
@Component
public final class ContentSearchControlInitializer implements ApplicationRunner {
    private final ContentWriteGateService gate;

    public ContentSearchControlInitializer(ContentWriteGateService gate) {
        this.gate = gate;
    }

    @Override
    public void run(ApplicationArguments args) {
        gate.initialize();
    }
}
