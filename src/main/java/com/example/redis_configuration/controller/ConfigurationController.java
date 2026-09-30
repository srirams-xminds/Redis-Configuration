package com.example.redis_configuration.controller;

import com.example.redis_configuration.entity.ConfigurationEntity;
import com.example.redis_configuration.service.ConfigurationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/configurations")
public class ConfigurationController
{
    private final ConfigurationService service;
    public ConfigurationController(ConfigurationService service)
    {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ConfigurationEntity getConfiguration(@PathVariable Long id)
    {
        return service.getConfiguration(id);
    }

    @GetMapping
    public List<ConfigurationEntity> getAllConfigurations()
    {
        return service.getAllConfigurations();
    }

    @PostMapping
    public ConfigurationEntity createConfiguration(@RequestBody ConfigurationEntity entity)
    {
        return service.saveConfiguration(entity);
    }

    @PutMapping("/{id}")
    public ConfigurationEntity updateConfiguration(@PathVariable Long id, @RequestBody ConfigurationEntity updatedConfig)
    {
        return service.updateConfiguration(id, updatedConfig);
    }

    @DeleteMapping("/{id}")
    public void deleteConfiguration(@PathVariable Long id)
    {
        service.deleteConfiguration(id);
    }
}