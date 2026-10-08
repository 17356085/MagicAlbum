package com.example.demo.auth.dto.apple;

public class AppleJwtHeader {
    private String kid;
    private String alg;

    public String getKid() {
        return kid;
    }

    public void setKid(String kid) {
        this.kid = kid;
    }

    public String getAlg() {
        return alg;
    }

    public void setAlg(String alg) {
        this.alg = alg;
    }
}
