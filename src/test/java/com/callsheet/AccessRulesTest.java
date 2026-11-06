package com.callsheet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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

    @Test
    void productionHouseCanOpenAResumeButCannotUploadOne() throws Exception {
        String p1 = login("p1");
        mvc.perform(get("/api/actors/a1/resume").header("Authorization", p1)).andExpect(status().isOk());
        MockMultipartFile pdf = new MockMultipartFile("file", "cv.pdf", "application/pdf", "%PDF-1.4 test".getBytes());
        mvc.perform(multipart("/api/actors/a3/resume").file(pdf).header("Authorization", p1))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanUploadPdfButNotOtherFiles() throws Exception {
        String m1 = login("m1");
        MockMultipartFile pdf = new MockMultipartFile("file", "cv.pdf", "application/pdf", "%PDF-1.4 test".getBytes());
        mvc.perform(multipart("/api/actors/a3/resume").file(pdf).header("Authorization", m1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resume.filename").value("cv.pdf"));
        MockMultipartFile text = new MockMultipartFile("file", "cv.pdf", "application/pdf", "not a pdf".getBytes());
        mvc.perform(multipart("/api/actors/a3/resume").file(text).header("Authorization", m1))
                .andExpect(status().isBadRequest());
    }

    @Test
    void managerCannotUploadForAnotherManagersActor() throws Exception {
        MockMultipartFile pdf = new MockMultipartFile("file", "cv.pdf", "application/pdf", "%PDF-1.4 test".getBytes());
        mvc.perform(multipart("/api/actors/a4/resume").file(pdf).header("Authorization", login("m1")))
                .andExpect(status().isForbidden());
    }
}
