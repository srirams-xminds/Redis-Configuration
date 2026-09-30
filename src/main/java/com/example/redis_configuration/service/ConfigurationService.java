package com.example.redis_configuration.service;

import com.example.redis_configuration.entity.ConfigurationEntity;
import com.example.redis_configuration.repository.ConfigurationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class ConfigurationService
{
    private final ConfigurationRepository repository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    public ConfigurationService(ConfigurationRepository repository, StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper)
    {
        this.repository = repository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public ConfigurationEntity getConfiguration(Long id)
    {
        String cacheKey = "config:" + id;
        String lockKey = "lock:config:" + id;

        try        // Do I already have this configuration in Redis?
        {
            ConfigurationEntity cached = getCachedConfiguration(cacheKey);
            if (cached != null)
            {
                return cached;
            }
        }
        catch (DataAccessException e) // Configuration Not Found in Redis-> directly use MySQL
        {
            return repository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Configuration not found: " + id));
        }

        // Wait up to 20 seconds for another request to populate the cache
        long deadline = System.currentTimeMillis() + 20_000;    //The application will wait for another request to populate the cache for up to 20 seconds. Suppose CACHE MISS and another request is currently rebuilding cache. Instead of Hitting DB, Request waits for cache to be available
        while (System.currentTimeMillis() < deadline)
        {
            String lockToken = UUID.randomUUID().toString();  //Each Request --> Each Token
            Boolean lockAcquired;
            try
            {
                lockAcquired = stringRedisTemplate.opsForValue()
                        .setIfAbsent(lockKey, lockToken, LOCK_TTL); //Without Lock, Suppose 10 Requests-->Cache Miss --> 10 DB HITS. Instead, 10 Requests -> Cache Miss -> Request 1 gets Lock -> Request 1 gets in Redis -> Other 9 can Take from Redis
            }
            catch (DataAccessException e)
            {
                return repository.findById(id)
                        .orElseThrow(() -> new RuntimeException("Configuration not found: " + id));
            }

            if (Boolean.TRUE.equals(lockAcquired)) //Rebuilding Cache. This request got the lock
            {
                try
                {
                    ConfigurationEntity cached = getCachedConfiguration(cacheKey);
                    if (cached != null) // Second Cache Check Because another request may have populated Redis just before this request acquired the lock.
                    {
                        return cached;
                    }

                    ConfigurationEntity entity = repository.findById(id) //Works when Redis Miss+Lock --> MySQL Becomes source
                                    .orElseThrow(() -> new RuntimeException("Configuration not found: " + id));

                    try
                    {
                        cacheIfNewer(id, entity);
                    }
                    catch (DataAccessException ignored)
                    {
                        // Redis failure should not prevent returning DB data.
                    }

                    return entity; // Client gets Configuration

                }
                finally
                {
                    try
                    {
                        releaseLock(lockKey, lockToken); //If something goes wrong, Lock released
                    }
                    catch (DataAccessException ignored)
                    {
                        // The lock has a TTL and will expire automatically.
                    }
                }
            }

            try // Another request owns the lock. Wait 100 milliseconds.
            {
                Thread.sleep(100);
            }

            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for cache", e);
            }

            // Check whether another instance has rebuilt the cache.
            try
            {
                ConfigurationEntity cached = getCachedConfiguration(cacheKey);
                if (cached != null)
                {
                    return cached;
                }
            }
            catch (DataAccessException e)
            {
                return repository.findById(id)
                        .orElseThrow(() -> new RuntimeException("Configuration not found: " + id));
            }
        }

        // If the wait times out, fall back to MySQL.
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Configuration not found: " + id));
    }

    // Read JSON from Redis and convert to Configuration Entity
    private ConfigurationEntity getCachedConfiguration(String cacheKey)
    {
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        if (json == null)
        {
            return null;
        }

        try
        {
            return objectMapper.readValue(json, ConfigurationEntity.class); //Convert JSON to Configuration Entity
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to deserialize cached configuration", e);
        }
    }

    // Configuration put into Redis without allowing deleted data to return
    private boolean cacheIfNewer(Long id, ConfigurationEntity entity)
    {
        String cacheKey = "config:" + id;
        String versionKey = "config:" + id + ":version";
        String deletedKey = "config:" + id + ":deleted";
        try
        {
            // Check whether configuration was deleted
            Boolean deleted = stringRedisTemplate.hasKey(deletedKey);
            if (Boolean.TRUE.equals(deleted))
            {
                return false;
            }

            // Get current Redis version
            String currentVersion = stringRedisTemplate.opsForValue().get(versionKey);

            // Reject stale version
            if (currentVersion != null)
            {
                long redisVersion = Long.parseLong(currentVersion);
                long incomingVersion = entity.getVersion();
                if (incomingVersion < redisVersion)
                {
                    return false;
                }
            }

            // Convert entity to JSON
            String json = objectMapper.writeValueAsString(entity);

            // Store new version
            stringRedisTemplate.opsForValue().set(versionKey, String.valueOf(entity.getVersion()));

            // Store configuration with 5-minute TTL
            stringRedisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL);
            return true;

        }
        catch (JsonProcessingException e)
        {
            throw new RuntimeException("Failed to serialize configuration", e);
        }
        catch (DataAccessException e)
        {
            throw e;
        }
    }

    // Create or update
    public ConfigurationEntity saveConfiguration(ConfigurationEntity entity)
    {
        ConfigurationEntity saved = repository.save(entity); //DB Updated First
        try
        {
            cacheIfNewer(saved.getId(), saved); //Redis Updated
        }
        catch (DataAccessException e)
        {
            // Ignore Redis failure. The database save is complete.
        }

        return saved;
    }

    public List<ConfigurationEntity> getAllConfigurations()
    {
        return repository.findAll();
    }

    public ConfigurationEntity updateConfiguration(Long id, ConfigurationEntity updatedConfig)
    {
        ConfigurationEntity existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Configuration not found: " + id));

        existing.setConfigKey(updatedConfig.getConfigKey());    //Changing Values
        existing.setConfigValue(updatedConfig.getConfigValue());       //Changing Values
        return saveConfiguration(existing); //Save to MySQL and update Redis.
    }

    public void deleteConfiguration(Long id)
    {
        repository.deleteById(id);
        String cacheKey = "config:" + id;
        String deletedKey = "config:" + id + ":deleted";
        try
        {
            // Mark as deleted
            stringRedisTemplate.opsForValue().set(deletedKey, "1");

            // Remove cached configuration
            stringRedisTemplate.delete(cacheKey);

        }
        catch (DataAccessException ignored)
        {
            // Database deletion succeeds even if Redis is unavailable.
        }
    }

    // Release Redis lock
    private void releaseLock(String lockKey, String lockToken)
    {
        String currentToken = stringRedisTemplate.opsForValue().get(lockKey);
        if (lockToken.equals(currentToken)) // Only delete the lock if this request still owns it.
        {
            stringRedisTemplate.delete(lockKey);
        }
    }
}