package io.skis.core.id;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Provides identifiers through a configurable generator with a default fallback. @Author: xhu */
public class IdWorker {

  private static final Logger logger = LoggerFactory.getLogger(IdWorker.class);
  private static final IdGenerator DEFAULT_ID_GENERATOR = SnowFlowerIdGenerator.getInstance();
  private static volatile IdWorker instance = null;
  private IdGenerator generator;

  private IdWorker(IdGenerator generator) {
    this.generator = generator;
  }

  public static synchronized IdWorker getInstance() {
    if (instance == null) {
      synchronized (IdWorker.class) {
        if (instance == null) {
          instance = new IdWorker(DEFAULT_ID_GENERATOR);
        }
      }
    }

    return instance;
  }

  public void setGenerator(@NonNull IdGenerator generator) {
    this.generator = generator;
  }

  public long nextId() {
    try {
      return this.generator.getId();
    } catch (Exception ex) {
      logger.debug(
          "{} Failed to obtain the ID. Using the default ID generator instead.",
          this.generator.getClass());
      return DEFAULT_ID_GENERATOR.getId();
    }
  }
}
