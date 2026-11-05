package com.callsheet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The rules that matter most: who can see and change what. */
@SpringBootTest
@AutoConfigureMockMvc
class AccessRulesTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private String login(String userId) throws Exception {
        String body = mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + om.readTree(body).get("token").asText();
    }

    @Test
    void requestsWithoutLoginAreRejected() throws Exception {
        mvc.perform(get("/api/actors")).andExpect(status().isUnauthorized());
    }

    @Test
    void managerCannotOpenAnotherManagersActor() throws Exception {
        mvc.perform(get("/api/actors/a4").header("Authorization", login("m1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerListOnlyContainsOwnActors() throws Exception {
        mvc.perform(get("/api/actors").header("Authorization", login("m1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void productionHouseSeesBusyButNeverTheReason() throws Exception {
        mvc.perform(get("/api/actors/a1").header("Authorization", login("p1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].type").value("BUSY"))
                .andExpect(jsonPath("$.blocks[0].note").doesNotExist());
    }

    @Test
    void productionHouseCannotChangeAvailability() throws Exception {
        mvc.perform(post("/api/actors/a1/availability")
                        .header("Authorization", login("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"start\":\"2030-01-01\",\"end\":\"2030-01-02\",\"type\":\"VACATION\"}"))
                .andExpect(status().isForbidden());
    }
}
