package com.railflow.train;

import com.railflow.common.ApiException;
import com.railflow.common.SeatBitmap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class TrainService {
    private final JdbcTemplate jdbc;
    public TrainService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record TrainResult(long trainId, String trainNumber, LocalDate runDate, String fromStation,
                              String toStation, LocalTime departureTime, LocalTime arrivalTime,
                              int startStation, int endStation) {}
    public record SeatResult(long seatId, int coachNo, String seatNo, int bitmap) {}
    public List<TrainResult> search(String from, String to, LocalDate date) {
        String origin = from.trim(), destination = to.trim();
        if (origin.isEmpty() || destination.isEmpty()) throw new ApiException(400, "站点编码不能为空");
        if (origin.equals(destination)) throw new ApiException(400, "出发站和到达站不能相同");
        Map<String, Long> stations = new HashMap<>();
        jdbc.query("SELECT id,code FROM stations WHERE code IN (?,?)", rs -> { stations.put(rs.getString("code"), rs.getLong("id")); }, origin, destination);
        if (!stations.containsKey(origin) || !stations.containsKey(destination)) throw new ApiException(404, "站点编码不存在");
        // COUNT preceding stops yields a zero-based position even with gapped/zero-based stop_order values.
        return jdbc.query("""
                SELECT t.id,t.train_number,t.run_date,o.departure_time,d.arrival_time,
                  (SELECT COUNT(*) FROM train_stops s WHERE s.train_id=t.id AND s.stop_order<o.stop_order) AS start_index,
                  (SELECT COUNT(*) FROM train_stops s WHERE s.train_id=t.id AND s.stop_order<d.stop_order) AS end_index
                FROM trains t JOIN train_stops o ON o.train_id=t.id JOIN train_stops d ON d.train_id=t.id
                WHERE t.run_date=? AND o.station_id=? AND d.station_id=? AND o.stop_order<d.stop_order
                  AND o.departure_time IS NOT NULL AND d.arrival_time IS NOT NULL
                ORDER BY o.departure_time,t.id
                """, (rs, n) -> new TrainResult(rs.getLong("id"), rs.getString("train_number"), rs.getDate("run_date").toLocalDate(),
                origin, destination, rs.getTime("departure_time").toLocalTime(), rs.getTime("arrival_time").toLocalTime(),
                rs.getInt("start_index"), rs.getInt("end_index")), date, stations.get(origin), stations.get(destination));
    }
    public List<SeatResult> available(long trainId, int start, int end) {
        if (trainId <= 0) throw new ApiException(422, "train_id 必须大于 0");
        int mask = SeatBitmap.mask(start, end);
        return jdbc.query("SELECT id,coach_no,seat_no,bitmap FROM train_seats WHERE train_id=? ORDER BY id",
                (rs, n) -> new SeatResult(rs.getLong("id"), rs.getInt("coach_no"), rs.getString("seat_no"), rs.getInt("bitmap")), trainId)
                .stream().filter(seat -> SeatBitmap.available(seat.bitmap(), mask)).toList();
    }
}
