package com.coin.coin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
@RequiredArgsConstructor
public class ApiService {

    public String upApi() {

        RestTemplate restTemplate = new RestTemplate();



        return "succes";
    }
}
