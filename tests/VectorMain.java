package com.jonathancruzate.musegadgetshield;

/** Desktop entry point for the in-APK SelfTest (SDK test vectors). */
public final class VectorMain {
    public static void main(String[] args) {
        SelfTest.run(new SelfTest.Logger() {
            @Override public void log(String message) { System.out.println(message); }
        });
    }
}
