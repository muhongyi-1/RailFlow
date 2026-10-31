package com.railflow.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.railflow.db.DatabaseInitializer;
import com.railflow.train.TrainService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DatabaseInitializer initializer;
    @Autowired TrainService trains;
    @Autowired ObjectMapper mapper;
    long trainId, seatId;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM orders"); jdbc.update("DELETE FROM train_seats"); jdbc.update("DELETE FROM train_stops");
        jdbc.update("DELETE FROM trains"); jdbc.update("DELETE FROM stations");
        initializer.seed();
        trainId=jdbc.queryForObject("SELECT id FROM trains LIMIT 1",Long.class);
        seatId=jdbc.queryForObject("SELECT id FROM train_seats ORDER BY id LIMIT 1",Long.class);
    }
    String booking(String requestId,int start,int end) {
        return """
                {"user_id":1,"request_id":"%s","train_id":%d,"seat_id":%d,"start_station":%d,"end_station":%d}
                """.formatted(requestId,trainId,seatId,start,end);
    }
    @Test void healthAndRoot() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        mvc.perform(get("/")).andExpect(jsonPath("$.service").value("RailFlow"));
    }
    @Test void forwardMiddleReverseAndDate() throws Exception {
        mvc.perform(get("/api/v1/trains/search").param("from_station"," BJS ").param("to_station","NJN").param("date",LocalDate.now().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].start_station").value(0)).andExpect(jsonPath("$[0].end_station").value(2));
        assertThat(trains.search("JNX","NJN",LocalDate.now()).get(0).startStation()).isEqualTo(1);
        assertThat(trains.search("NJN","BJS",LocalDate.now())).isEmpty();
        assertThat(trains.search("BJS","SHH",LocalDate.now().plusDays(1))).isEmpty();
    }
    @Test void gappedAndZeroBasedStopOrders() {
        jdbc.update("UPDATE train_stops SET stop_order=stop_order*10");
        assertThat(trains.search("BJS","NJN",LocalDate.now()).get(0).endStation()).isEqualTo(2);
        jdbc.update("UPDATE train_stops SET stop_order=stop_order/10-1");
        assertThat(trains.search("BJS","NJN",LocalDate.now()).get(0).endStation()).isEqualTo(2);
    }
    @Test void rejectsBadQueries() throws Exception {
        mvc.perform(get("/api/v1/trains/search").param("from_station","BJS").param("to_station","BJS").param("date",LocalDate.now().toString())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/trains/search").param("from_station","BJS").param("to_station","UNKNOWN").param("date",LocalDate.now().toString())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/trains/search").param("from_station","BJS").param("to_station","SHH").param("date","invalid")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/seats/available").param("train_id","1").param("start_station","0").param("end_station","32")).andExpect(status().isUnprocessableEntity());
    }
    @Test void bookingReplayConflictAndOwnership() throws Exception {
        String response=mvc.perform(post("/api/v1/orders").contentType("application/json").content(booking("one",0,2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.request_mask").value(3)).andExpect(jsonPath("$.amount").value("0.00"))
                .andReturn().getResponse().getContentAsString();
        String orderNo=mapper.readTree(response).get("order_no").asText();
        mvc.perform(post("/api/v1/orders").contentType("application/json").content(booking("one",0,2))).andExpect(jsonPath("$.order_no").value(orderNo));
        mvc.perform(post("/api/v1/orders").contentType("application/json").content(booking("one",2,3))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/orders").contentType("application/json").content(booking("overlap",1,3))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/orders").contentType("application/json").content(booking("adjacent",2,3))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders/"+orderNo).param("user_id","1")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders/"+orderNo).param("user_id","2")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders",Integer.class)).isEqualTo(2);
    }
    @Test void rejectsUnknownFieldsAndInvalidIntervals() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType("application/json").content(booking("bad",2,2))).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/orders").contentType("application/json").content(booking("bad",0,2).replace("}",",\"amount\":1}"))).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/orders").contentType("application/json").content(booking("bad space",0,2))).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/orders").contentType("application/json").content(booking("bad",0,2).replace("\"seat_id\":"+seatId,"\"seat_id\":null"))).andExpect(status().isUnprocessableEntity());
    }
    @Test void seatQueryReflectsOccupancyAndSeedPreservesIt() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType("application/json").content(booking("one",0,2))).andExpect(status().isOk());
        initializer.seed();
        assertThat(trains.available(trainId,0,2)).hasSize(2);
        assertThat(trains.available(trainId,2,3)).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT bitmap FROM train_seats WHERE id=?",Integer.class,seatId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trains",Integer.class)).isEqualTo(1);
    }
    @Test void apiDocumentationIsAvailable() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/v1/orders']").exists());
    }
}
