package cn.iocoder.yudao.module.skit.controller.app.member.vo.point;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitMemberCheckInRespVOTest {

    @Test
    void signInDateUsesIsoDateWhenGlobalDatesAreWrittenAsTimestamps() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        SkitMemberCheckInRespVO response = new SkitMemberCheckInRespVO();
        response.setSignInDate(LocalDate.of(2026, 7, 24));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(response));

        assertTrue(json.get("signInDate").isTextual(), json.toString());
        assertEquals("2026-07-24", json.get("signInDate").textValue());
    }

}
