package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PreferenceProfileService {

    private final Map<String, PreferenceProfile> profilesByUser = new ConcurrentHashMap<>();

    public PreferenceProfile getProfile(String userId) {
        return profilesByUser.getOrDefault(userId, PreferenceProfile.empty());
    }

    public PreferenceProfile updateProfile(String userId, PreferenceProfile incoming) {
        if (incoming == null) {
            return getProfile(userId);
        }
        return profilesByUser.compute(userId, (key, existing) -> {
            PreferenceProfile base = existing == null ? PreferenceProfile.empty() : existing;
            return base.merge(incoming);
        });
    }
}

