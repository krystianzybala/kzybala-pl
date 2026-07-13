package pl.kzybala.lab.falsesharing;

import jdk.internal.vm.annotation.Contended;

/**
 * {@code @Contended} lives in the internal package
 * {@code jdk.internal.vm.annotation} — compiling and running this class
 * requires {@code --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED}
 * (wired into this project's pom.xml and JMH fork args). No cross-JDK-version
 * compatibility promise is made for internal packages; prefer this over
 * manual padding only when you control the deployment JVM flags. See java.md.
 */
public class ContendedCounters {
    @Contended
    public volatile long counterA;
    @Contended
    public volatile long counterB;
}
