package ai.capri.fixture;
public final class Fixture {
    public static int shippingFee(int subtotal) {
        if (subtotal >= 100) return 0;
        return 12;
    }
}
