package com.example.redis_configuration.repository;

import com.example.redis_configuration.entity.ConfigurationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigurationRepository extends JpaRepository<ConfigurationEntity, Long>
{

}