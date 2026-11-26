package java.lang.invoke;

/** Compile-only signatures for ECJ. D8 desugars the resulting call sites for Android. */
public final class LambdaMetafactory {
    private LambdaMetafactory() {}

    public static CallSite metafactory(MethodHandles.Lookup lookup, String name, MethodType invokedType,
            MethodType samType, MethodHandle implementation, MethodType instantiatedType)
            throws LambdaConversionException {
        throw new AssertionError("Compile-only class");
    }

    public static CallSite altMetafactory(MethodHandles.Lookup lookup, String name, MethodType invokedType,
            Object... arguments) throws LambdaConversionException {
        throw new AssertionError("Compile-only class");
    }
}
