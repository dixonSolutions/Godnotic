package io.github.dixonsolutions.godnotic.fe;

import static org.junit.Assert.*;
import org.junit.Test;

public class EscapeKeyRouteTest {
    @Test public void openingPressCannotCloseTheMenuOnReleaseOrLaterFrames() {
        EscapeKeyRoute route = new EscapeKeyRoute();
        boolean open = false;
        assertEquals(EscapeKeyRoute.ENGINE, route.event(1, 111, true, open, false));
        open = true; // engine opens it on the first down
        for (int frame = 0; frame < 3; frame++) assertTrue(open);
        int release = route.event(1, 111, false, open, false);
        if (release == EscapeKeyRoute.BACK) open = false;
        assertEquals(EscapeKeyRoute.ENGINE, release);
        assertTrue(open);
        assertEquals(EscapeKeyRoute.OVERLAY, route.event(1, 111, true, open, false));
        if (route.event(1, 111, false, open, false) == EscapeKeyRoute.BACK) open = false;
        assertFalse(open);
    }

    @Test public void repeatsCancellationAndIndependentKeysKeepTheirOwner() {
        EscapeKeyRoute route = new EscapeKeyRoute();
        assertEquals(EscapeKeyRoute.OVERLAY, route.event(1, 111, true, true, false));
        assertEquals(EscapeKeyRoute.OVERLAY, route.event(1, 111, true, false, false));
        assertEquals(EscapeKeyRoute.ENGINE, route.event(2, 111, true, false, false));
        assertEquals(EscapeKeyRoute.ENGINE, route.event(2, 111, false, true, false));
        assertEquals(EscapeKeyRoute.OVERLAY, route.event(1, 111, false, true, true));
        assertEquals(EscapeKeyRoute.OVERLAY, route.event(1, 111, false, true, false));
    }
}
