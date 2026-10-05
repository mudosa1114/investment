package com.coin.coin.controller;

import com.coin.coin.service.DataExportService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 분석용 데이터 내보내기 페이지 (10/6 추가) — GET /export
 * 날짜(yyyy-MM-dd)를 고르면 그 날짜 00:00 이후 데이터를 테이블별 CSV 또는 전체 ZIP으로 내려준다.
 */
@Controller
@RequestMapping("/export")
@RequiredArgsConstructor
public class DataExportController {

    private final DataExportService exportService;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("tables", exportService.tables());
        model.addAttribute("today", LocalDate.now().toString());
        return "export";
    }

    @GetMapping("/all")
    public void all(@RequestParam("date") String date, HttpServletResponse res) throws IOException {
        LocalDate from = parse(date);
        attachment(res, "application/zip", "coin_export_" + from + "~.zip");
        exportService.writeZip(from, res.getOutputStream());
    }

    @GetMapping("/{table}")
    public void table(@PathVariable("table") String table, @RequestParam("date") String date,
                      HttpServletResponse res) throws IOException {
        if (!exportService.isExportable(table)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "내보낼 수 없는 테이블: " + table);
        }
        LocalDate from = parse(date);
        attachment(res, "text/csv; charset=UTF-8", exportService.fileName(table, from));
        exportService.writeCsv(table, from, res.getOutputStream());
    }

    private LocalDate parse(String date) {
        try {
            return LocalDate.parse(date); // yyyy-MM-dd
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "날짜는 yyyy-MM-dd 형식이어야 합니다: " + date);
        }
    }

    private void attachment(HttpServletResponse res, String contentType, String fileName) {
        res.setContentType(contentType);
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        res.setHeader("Content-Disposition", "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
    }
}
