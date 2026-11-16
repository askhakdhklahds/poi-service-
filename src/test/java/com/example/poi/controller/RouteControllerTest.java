package com.example.poi.controller;

import com.example.poi.security.JwtAuthFilter;
import com.example.poi.security.JwtService;
import com.example.poi.service.route.RoutePlanService;
import com.example.poi.service.route.RoutePoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RouteController.class)
@AutoConfigureMockMvc(addFilters = false)
class RouteControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private RoutePlanService service;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private JwtService jwtService;

    @Test
    void planReturnsRoutes() throws Exception {
        given(service.plan(any(RoutePoint.class), any(RoutePoint.class),
                anyList(), anyInt())).willReturn(Map.of(
                "routes", List.of(Map.of(
                        "routeId", "car-1",
                        "mode", "car",
                        "distanceMeters", 1773))));

        mvc.perform(post("/api/route/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"from":{"lon":121.49,"lat":31.24,"name":"A"},
                                 "to":{"lon":121.505,"lat":31.24,"name":"B"},
                                 "modes":["car","walk"],"alternatives":3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routes[0].routeId").value("car-1"))
                .andExpect(jsonPath("$.data.routes[0].mode").value("car"));
    }

    @Test
    void planRequiresFromAndTo() throws Exception {
        mvc.perform(post("/api/route/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void planRejectsOutOfRangeCoordinate() throws Exception {
        mvc.perform(post("/api/route/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"from":{"lon":200,"lat":31.24,"name":"A"},
                                 "to":{"lon":121.505,"lat":31.24,"name":"B"}}
                                """))
                .andExpect(status().isBadRequest());
    }
}
