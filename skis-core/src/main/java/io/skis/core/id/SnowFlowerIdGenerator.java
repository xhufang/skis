package io.skis.core.id;

import java.security.SecureRandom;

/** Generates distributed identifiers using a Snowflake-style algorithm. @Author: xhu */
public class SnowFlowerIdGenerator implements IdGenerator {

  private static final SnowFlowerIdGenerator INSTANCE = new SnowFlowerIdGenerator();
  private final long workerId;
  private final long datacenterId;
  private long sequence = 0L;
  private long lastTimestamp = -1L;

  public SnowFlowerIdGenerator() {
    this.datacenterId = (new SecureRandom()).nextInt(31);
    this.workerId = (new SecureRandom()).nextInt(31);
  }

  public static SnowFlowerIdGenerator getInstance() {
    return INSTANCE;
  }

  public synchronized long nextId() {
    long timestamp = this.timeGen();
    if (timestamp < this.lastTimestamp) {
      throw new RuntimeException(
          String.format(
              "Clock moved backwards.  Refusing to generate id for %d milliseconds",
              this.lastTimestamp - timestamp));
    } else {
      if (this.lastTimestamp == timestamp) {
        this.sequence = this.sequence + 1L & 4095L;
        if (this.sequence == 0L) {
          timestamp = this.tilNextMillis(this.lastTimestamp);
        }
      } else {
        this.sequence = 0L;
      }
      this.lastTimestamp = timestamp;
      long twepoch = 14832288000000L;
      return timestamp - twepoch << 22L
          | this.datacenterId << 17L
          | this.workerId << 12L
          | this.sequence;
    }
  }

  private long tilNextMillis(long lastTimestamp) {
    long timestamp;
    for (timestamp = this.timeGen(); timestamp <= lastTimestamp; timestamp = this.timeGen()) {}
    return timestamp;
  }

  protected long timeGen() {
    return System.currentTimeMillis();
  }

  @Override
  public long getId() {
    return nextId();
  }
}
