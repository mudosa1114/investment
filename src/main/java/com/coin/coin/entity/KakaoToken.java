package com.coin.coin.entity;

import com.coin.coin.dto.KakaoAuthDto;
import jakarta.persistence.*;
import lombok.Getter;

@Entity
@Getter
@Table(schema = "public", name = "token")
public class KakaoToken {

    @Column(name = "access_token")
    private String accessToken;
    @Column(name = "refresh_token")
    private String refreshToken;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "no")
    private Integer no;

    public static KakaoToken updateToken(KakaoAuthDto dto) {
        KakaoToken kakaoToken = new KakaoToken();
        kakaoToken.accessToken = dto.getAccessToken();
        kakaoToken.refreshToken = dto.getRefreshToken();
        kakaoToken.no = 1;
        return kakaoToken;
    }
}
