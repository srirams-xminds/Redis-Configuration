package com.example.redis_configuration.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig
{
    @Bean   //Create ObjectMapper Object and makes it available for dependency injection
    public ObjectMapper redisObjectMapper()
    {
        ObjectMapper objectMapper = new ObjectMapper(); //Conversion between JSON AND ConfigurationEntity
        objectMapper.registerModule(new JavaTimeModule());  //LocateDateTime to JSON
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);   //Represent Time as JSON
        return objectMapper;
    }
}