package com.railflow.db;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.util.List;

@Component
public class DatabaseInitializer {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public DatabaseInitializer(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager);
    }
    public void createTables() {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(jdbc.getDataSource());
        System.out.println("建表完成（已有表和数据保持不变）");
    }
    public void seed() {
        tx.executeWithoutResult(status -> {
            String[][] stations = {{"BJS", "北京南"}, {"JNX", "济南西"}, {"NJN", "南京南"}, {"SHH", "上海虹桥"}};
            for (String[] station : stations) {
                if (jdbc.queryForObject("SELECT COUNT(*) FROM stations WHERE code=?", Integer.class, station[0]) == 0)
                    jdbc.update("INSERT INTO stations(code,name) VALUES (?,?)", station[0], station[1]);
            }
            LocalDate today = LocalDate.now();
            List<Long> trains = jdbc.queryForList("SELECT id FROM trains WHERE train_number='G1001' AND run_date=?", Long.class, today);
            if (trains.isEmpty()) jdbc.update("INSERT INTO trains(train_number,run_date) VALUES ('G1001',?)", today);
            Long trainId = jdbc.queryForObject("SELECT id FROM trains WHERE train_number='G1001' AND run_date=? FOR UPDATE", Long.class, today);
            if (trains.isEmpty()) {
                String[] arrival = {null, "09:25:00", "11:10:00", "12:30:00"};
                String[] departure = {"08:00:00", "09:30:00", "11:15:00", null};
                for (int i = 0; i < stations.length; i++) {
                    Long stationId = jdbc.queryForObject("SELECT id FROM stations WHERE code=?", Long.class, stations[i][0]);
                    jdbc.update("INSERT INTO train_stops(train_id,station_id,stop_order,arrival_time,departure_time) VALUES (?,?,?,?,?)",
                            trainId, stationId, i + 1, arrival[i], departure[i]);
                }
            }
            for (String seat : List.of("A01", "A02", "A03")) {
                if (jdbc.queryForObject("SELECT COUNT(*) FROM train_seats WHERE train_id=? AND seat_no=?", Integer.class, trainId, seat) == 0)
                    jdbc.update("INSERT INTO train_seats(train_id,coach_no,seat_no,seat_type,bitmap,version) VALUES (?,8,?,'二等座',0,0)", trainId, seat);
            }
        });
        System.out.println("演示车次和座位初始化完成（未修改已有座位 Bitmap）");
    }
}
