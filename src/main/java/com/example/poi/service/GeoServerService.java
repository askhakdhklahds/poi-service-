package com.example.poi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class GeoServerService {

    private static final String WORKSPACE_JSON = """
            {"workspace":{"name":"poi"}}
            """;

    private static final String DATASTORE_JSON = """
            {"dataStore":{
              "name":"poi_pg",
              "type":"PostGIS",
              "enabled":true,
              "connectionParameters":{"entry":[
                {"@key":"host","$":"127.0.0.1"},
                {"@key":"port","$":"5432"},
                {"@key":"database","$":"poi"},
                {"@key":"schema","$":"public"},
                {"@key":"user","$":"postgres"},
                {"@key":"passwd","$":"postgres"},
                {"@key":"dbtype","$":"postgis"}
              ]}
            }}
            """;

    private static final String DISTRICT_LAYER_JSON = """
            {"featureType":{
              "name":"district","nativeName":"district","title":"Shanghai Districts",
              "srs":"EPSG:4326","projectionPolicy":"FORCE_DECLARED","enabled":true
            }}
            """;

    private static final String POI_LAYER_JSON = """
            {"featureType":{
              "name":"poi","nativeName":"poi","title":"Shanghai POI",
              "srs":"EPSG:4326","projectionPolicy":"FORCE_DECLARED","enabled":true
            }}
            """;

    private final RestClient client;

    public GeoServerService(@Value("${geoserver.url}") String url,
                            @Value("${geoserver.username}") String username,
                            @Value("${geoserver.password}") String password) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(60000);
        this.client = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(url + "/rest")
                .defaultHeaders(h -> h.setBasicAuth(username, password))
                .build();
    }

    public Map<String, Object> publish() {
        List<String> steps = new ArrayList<>();
        steps.add("workspace: " + ensure("/workspaces/poi", "/workspaces", WORKSPACE_JSON));
        steps.add("datastore: " + ensure(
                "/workspaces/poi/datastores/poi_pg",
                "/workspaces/poi/datastores", DATASTORE_JSON));
        steps.add("district layer: " + ensure(
                "/workspaces/poi/datastores/poi_pg/featuretypes/district",
                "/workspaces/poi/datastores/poi_pg/featuretypes", DISTRICT_LAYER_JSON));
        steps.add("poi layer: " + ensure(
                "/workspaces/poi/datastores/poi_pg/featuretypes/poi",
                "/workspaces/poi/datastores/poi_pg/featuretypes", POI_LAYER_JSON));
        return Map.of("code", 0, "message", "publish finished", "steps", steps);
    }

    private String ensure(String checkPath, String createPath, String body) {
        if (exists(checkPath)) {
            return "exists";
        }
        try {
            client.post().uri(createPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return "created";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private boolean exists(String path) {
        try {
            client.get().uri(path).retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
