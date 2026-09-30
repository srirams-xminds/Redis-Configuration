package com.example.redis_configuration.service;

import com.example.redis_configuration.entity.ConfigurationEntity;
import com.example.redis_configuration.repository.ConfigurationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private static final String CACHE_IF_NEWER_SCRIPT =
            "if redis.call('exists', KEYS[3]) == 1 then return 0 end " +   //REQ A--> Reads old data from SQL, REQ B-->deletes config, REQ A-->Tries to put old data it Redis--> This line solves that
                    "local current = redis.call('get', KEYS[2]) " + //Current Redis Version Number
                    "if current and tonumber(ARGV[1]) < tonumber(current) " + //ARGV[1] is the incoming version.
                    "then return 0 end " + //Suppose Request A → version 5,Request B → version 3. If Request B(v3) finishes later, it shouldn't overwrite Redis version 5 with version 3.This block prevents that.
                    "redis.call('set', KEYS[2], ARGV[1]) " + //If incoming version acceptable, stored
                    "redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3]) " + //KEYS[1]-> config, ARGV[2]->JSON Conig, ARGV[3](TTL)->300 sec
                    "return 1";

    public ConfigurationService(ConfigurationRepository repository, StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper)
    {
        this.repository = repository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public ConfigurationEntity getConfiguration(Long id) {

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
        catch (DataAccessException e) // Configuration Not Found in Redis
        {
            return repository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Configuration not found: " + id));
        }

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

            if (Boolean.TRUE.equals(lockAcquired)) //Rebuilding Cache
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

            try //If Requests don't get lock, they wait 100 seconds. Then check Redis again
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
            String json = objectMapper.writeValueAsString(entity);  //Convert Configuration Entity to JSON
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(CACHE_IF_NEWER_SCRIPT, Long.class);
            Long result = stringRedisTemplate.execute(      //Execute Lua Script
                    script,
                    List.of(cacheKey, versionKey, deletedKey),
                    String.valueOf(entity.getVersion()),
                    json,
                    String.valueOf(CACHE_TTL.toSeconds())
            );

            return Long.valueOf(1).equals(result);
        }
        catch (DataAccessException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to cache configuration", e);
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

        String script = "redis.call('set', KEYS[2], '1') " + "redis.call('del', KEYS[1]) " + "return 1"; //It prevents an old request from later putting deleted data back into Redis.
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(script, Long.class);
        try
        {
            stringRedisTemplate.execute(redisScript, List.of(cacheKey, deletedKey));
        }
        catch (DataAccessException ignored)
        {
            // Database deletion succeeds even if Redis is unavailable.
        }
    }

    // Release Redis lock
    private void releaseLock(String lockKey, String lockToken)
    {
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] " + "then return redis.call('del', KEYS[1]) " + "else return 0 end";  //Delete the lock only if the current Redis lock value equals my token.
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(script, Long.class);
        stringRedisTemplate.execute(
                redisScript,
                List.of(lockKey),
                lockToken
        );
    }
}