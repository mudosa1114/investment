package com.coin.coin.config;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.coin.coin.dto.TradeRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class UpbitJwtGenerator {

    @Value("${upbit.access-key}")
    private String accessKey;

    @Value("${upbit.secret-key}")
    private String secretKey;

    public String upbitJwtToken() {
        Algorithm algorithm = Algorithm.HMAC256(secretKey);
        return JWT.create()
                .withClaim("access_key", accessKey)
                .withClaim("nonce", UUID.randomUUID().toString())
                .sign(algorithm);
    }

    public String upbitOrderToken(TradeRequest request) throws NoSuchAlgorithmException {
        Algorithm algorithm = Algorithm.HMAC512(secretKey.getBytes(StandardCharsets.UTF_8));

        String queryString = buildQueryString(request);

        MessageDigest md = MessageDigest.getInstance("SHA-512");
        md.update(queryString.getBytes(StandardCharsets.UTF_8));

        String queryHash = String.format("%0128x", new BigInteger(1, md.digest()));

        return JWT.create()
                .withClaim("access_key", accessKey)
                .withClaim("nonce", UUID.randomUUID().toString())
                .withClaim("query_hash", queryHash)
                .withClaim("query_hash_alg", "SHA512")
                .sign(algorithm);
    }

    private String buildQueryString(TradeRequest request) {
        List<String> queryList = new ArrayList<>();

        if (request.getMarket() != null) queryList.add("market=" + request.getMarket());
        if (request.getSide() != null) queryList.add("side=" + request.getSide());
        if (request.getVolume() != null) queryList.add("volume=" + request.getVolume());
        if (request.getPrice() != null) queryList.add("price=" + request.getPrice());
        if (request.getOrdType() != null) queryList.add("ord_type=" + request.getOrdType());
        if (request.getTimeInForce() != null) queryList.add("time_in_force=" + request.getTimeInForce());

        return String.join("&", queryList);
    }
}
