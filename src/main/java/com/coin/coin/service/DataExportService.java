package com.coin.coin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 분석용 DB 데이터 CSV 내보내기 (10/6 추가) — /export 페이지에서 사용.
 *
 * <p>테이블별로 "기준 날짜 컬럼 &gt;= 입력한 날짜 00:00" 인 행을 날짜순으로 내보낸다.
 * 테이블명은 아래 고정 목록에서만 고르므로 사용자 입력이 SQL에 직접 들어가지 않고,
 * 날짜는 LocalDate로 파싱한 뒤 바인딩 파라미터로 넘긴다.
 * CSV는 UTF-8(BOM 포함)이라 엑셀에서 한글이 깨지지 않는다.
 */
@Service
@RequiredArgsConstructor
public class DataExportService {

    /** 내보낼 테이블 → 날짜 필터 컬럼 */
    private static final Map<String, String> TABLES = new LinkedHashMap<>();
    static {
        TABLES.put("exit_review", "sell_time");
        TABLES.put("last_trade", "traded_at");
        TABLES.put("trade_history", "traded_at");
        TABLES.put("trade_result", "trade_date");
        TABLES.put("momentum_stop_shadow", "captured_at");
    }

    private final JdbcTemplate jdbcTemplate;

    public Set<String> tables() {
        return TABLES.keySet();
    }

    public boolean isExportable(String table) {
        return TABLES.containsKey(table);
    }

    /** 한 테이블을 CSV로 out에 쓴다 (out은 닫지 않음) */
    public void writeCsv(String table, LocalDate from, OutputStream out) throws IOException {
        String dateColumn = TABLES.get(table);
        if (dateColumn == null) {
            throw new IllegalArgumentException("내보낼 수 없는 테이블: " + table);
        }
        // trade_result.trade_date는 date, 나머지는 timestamp 컬럼
        Object param = "trade_date".equals(dateColumn) ? from : from.atStartOfDay();
        String sql = "select * from coin." + table + " where " + dateColumn + " >= ? order by " + dateColumn;

        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write('﻿'); // BOM — 엑셀 한글 깨짐 방지
        jdbcTemplate.query(sql, rs -> {
            try {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                StringBuilder header = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    if (i > 1) header.append(',');
                    header.append(csv(md.getColumnLabel(i)));
                }
                w.write(header.append("\r\n").toString());
                while (rs.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 1; i <= n; i++) {
                        if (i > 1) row.append(',');
                        Object v = rs.getObject(i);
                        if (v != null) {
                            row.append(csv(v instanceof BigDecimal b ? b.toPlainString() : v.toString()));
                        }
                    }
                    w.write(row.append("\r\n").toString());
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return null;
        }, param);
        w.flush();
    }

    /** 전체 테이블을 ZIP 하나로 (테이블별 CSV) */
    public void writeZip(LocalDate from, OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        for (String table : TABLES.keySet()) {
            zip.putNextEntry(new ZipEntry(fileName(table, from)));
            writeCsv(table, from, zip);
            zip.closeEntry();
        }
        zip.finish();
    }

    public String fileName(String table, LocalDate from) {
        return table + "_" + from + "~.csv";
    }

    private static String csv(String s) {
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
