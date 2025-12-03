package com.example.demo.Simulator.Controller;

import org.springframework.web.bind.annotation.*;

import com.example.demo.Simulator.Service.S3UploadService;

import com.example.demo.Simulator.Service.SimulatorService;

@RestController
@RequestMapping("/simulate")
public class SimulatorController {

    private final S3UploadService s3UploadService;
    private final SimulatorService simulatorService;

    public SimulatorController(S3UploadService s3UploadService,
            SimulatorService simulatorService) {
        this.s3UploadService = s3UploadService;
        this.simulatorService = simulatorService;
    }

    @GetMapping("/run")
    public String runSimulation() {
        try {
            simulatorService.run();
            return " Simulation complete. Check logs for per-file status.";
        } catch (Exception e) {
            e.printStackTrace();
            return " Error: " + e.getMessage();
        }
    }
}