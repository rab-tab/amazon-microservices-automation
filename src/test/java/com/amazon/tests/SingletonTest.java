package com.amazon.tests;

import com.amazon.tests.config.ConfigManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SingletonTest {
    public static void main(String[] args) {
        ConfigManager cm1=ConfigManager.getInstance();
        ConfigManager cm2=ConfigManager.getInstance();
        ConfigManager cm3=ConfigManager.getInstance();
        // Check if they're the SAME object
        System.out.println("cm1 == cm2: " + (cm1 == cm2)); // true
        System.out.println("cm2 == cm3: " + (cm2 == cm3)); // true
        System.out.println("cm1 == cm3: " + (cm1 == cm3)); // true

        // Check hash codes (same object = same hash code)
        System.out.println("cm1 hashCode: " + cm1.hashCode()); // e.g., 12345678
        System.out.println("cm2 hashCode: " + cm2.hashCode()); // e.g., 12345678
        System.out.println("cm3 hashCode: " + cm3.hashCode()); // e.g., 12345678


        ExecutorService executor= Executors.newFixedThreadPool(10);
        for(int i=0;i<10;i++){
            int threadNum=i;
            ConfigManager cm=ConfigManager.getInstance();
            System.out.println("Thread" +threadNum + cm.hashCode());
        }
        executor.shutdown();

    }
}
