package com.longOrder.api;

public class Test {
    public static void main(String[] args) {
        String str = "Hello"; // 测试 String
        System.out.println(str);

        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            System.out.println("Mac available");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}