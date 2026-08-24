package io.skis.metadata;

/** Runtime contract shared by generated entity models and the SKIS metadata library. */
public final class GeneratedModelAbi {

  /** ABI implemented by this runtime. */
  public static final int CURRENT = 3;

  private GeneratedModelAbi() {}

  /** Fails immediately when generated code was produced for a different metadata ABI. */
  public static void requireCompatible(int generatedAbi) {
    if (generatedAbi != CURRENT) {
      throw new IncompatibleClassChangeError(
          "SKIS generated-model ABI "
              + generatedAbi
              + " is incompatible with runtime ABI "
              + CURRENT);
    }
  }
}
