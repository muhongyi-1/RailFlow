CREATE TABLE IF NOT EXISTS stations (
 id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(16) NOT NULL UNIQUE, name VARCHAR(64) NOT NULL
);
CREATE TABLE IF NOT EXISTS trains (
 id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, train_number VARCHAR(20) NOT NULL, run_date DATE NOT NULL,
 CONSTRAINT uq_train_number_date UNIQUE (train_number, run_date), INDEX ix_trains_run_date(run_date)
);
CREATE TABLE IF NOT EXISTS train_stops (
 id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, train_id INT NOT NULL, station_id INT NOT NULL,
 stop_order INT NOT NULL, arrival_time TIME, departure_time TIME,
 FOREIGN KEY (train_id) REFERENCES trains(id), FOREIGN KEY (station_id) REFERENCES stations(id),
 CONSTRAINT uq_train_stop_order UNIQUE(train_id,stop_order), CONSTRAINT uq_train_stop_station UNIQUE(train_id,station_id),
 INDEX ix_train_stops_station_train(station_id,train_id)
);
CREATE TABLE IF NOT EXISTS train_seats (
 id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, train_id INT NOT NULL, coach_no INT NOT NULL,
 seat_no VARCHAR(10) NOT NULL, seat_type VARCHAR(20) NOT NULL DEFAULT '二等座',
 bitmap INT NOT NULL DEFAULT 0, version INT NOT NULL DEFAULT 0,
 FOREIGN KEY(train_id) REFERENCES trains(id), INDEX idx_train_id(train_id), INDEX idx_train_seat(train_id,coach_no,seat_no)
);
CREATE TABLE IF NOT EXISTS orders (
 id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, order_no VARCHAR(32) NOT NULL UNIQUE,
 user_id INT NOT NULL, request_id VARCHAR(64) NOT NULL, train_id INT NOT NULL, seat_id INT NOT NULL,
 start_station INT NOT NULL, end_station INT NOT NULL, request_mask INT NOT NULL,
 amount DECIMAL(10,2) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING_PAYMENT',
 created_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NOT NULL, paid_at DATETIME(6), closed_at DATETIME(6),
 FOREIGN KEY(train_id) REFERENCES trains(id), FOREIGN KEY(seat_id) REFERENCES train_seats(id),
 CONSTRAINT uq_orders_user_request UNIQUE(user_id,request_id),
 CONSTRAINT ck_orders_user CHECK(user_id>0),
 CONSTRAINT ck_orders_interval CHECK(start_station>=0 AND end_station>start_station AND end_station<=31),
 CONSTRAINT ck_orders_mask CHECK(request_mask>0), CONSTRAINT ck_orders_amount CHECK(amount>=0),
 CONSTRAINT ck_orders_status CHECK(status IN ('PENDING_PAYMENT','PAID','CANCELED','CLOSED')),
 INDEX idx_orders_user_created(user_id,created_at), INDEX idx_orders_status_expires(status,expires_at)
);
