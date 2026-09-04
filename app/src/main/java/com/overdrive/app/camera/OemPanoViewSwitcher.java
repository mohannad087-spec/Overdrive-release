package com.overdrive.app.camera;

import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;

/**
 * Alternate DiLink 4 camera-direction switch for byd_apa units where the
 * mosaic-viewpoint handshake in {@link BydApaViewpointHelper} never gets the
 * HAL out of single-camera mode (the {@code setIntArray} write to device
 * 1031 lands but the producer stays a single feed instead of a real 2x2).
 * On those units the one shared feed is still steerable — through a
 * DIFFERENT OEM service, {@code IBYDAutoPanoService}, which the native
 * panorama app itself calls to pick which physical direction that single
 * feed shows.
 *
 * <p>Reverse-engineered by a field user whose unit only ever produces an
 * image from "Direct camera 0", and where that image always mirrors
 * whatever direction the OEM app currently has selected — i.e. exactly the
 * unit this class targets. NOT verified end-to-end against the real
 * transaction numbers on that device. Every failure mode here (service
 * absent, transaction rejected, wrong argument shape for this firmware's
 * AIDL revision) degrades to a silent no-op and a warning log, so calling
 * this alongside the existing mosaic path is safe on units where the
 * mosaic path already works — it should just never successfully transact
 * there, since {@code IBYDAutoPanoService} wouldn't be in the same state a
 * real 2x2 producer implies.
 *
 * @see BydApaViewpointHelper
 */
public final class OemPanoViewSwitcher {

    private static final DaemonLogger logger = DaemonLogger.getInstance("OemPanoViewSwitcher");

    private static final String PANO_SERVICE =
        "android.hardware.bydauto.panorama.IBYDAutoPanoService";

    private static final int PANO_SET_VALUE_TRANSACTION = 2;
    private static final int PANO_VIEWPOINT_TYPE = 5;

    private static final int VIEW_FRONT = 2;
    private static final int VIEW_REAR  = 3;
    private static final int VIEW_LEFT  = 4;
    private static final int VIEW_RIGHT = 5;

    private OemPanoViewSwitcher() {
    }

    /**
     * Asks {@code IBYDAutoPanoService} to switch the single shared camera
     * feed to {@code view}. Best-effort — returns false and only logs a
     * warning on any failure. Never throws.
     */
    public static boolean setCameraDirection(CameraVirtualView view) {
        if (view == null) {
            return false;
        }
        switch (view) {
            case FRONT:
                return setOemPanoView(VIEW_FRONT);
            case REAR:
                return setOemPanoView(VIEW_REAR);
            case LEFT:
                return setOemPanoView(VIEW_LEFT);
            case RIGHT:
                return setOemPanoView(VIEW_RIGHT);
            default:
                return false;
        }
    }

    private static boolean setOemPanoView(int output) {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getDeclaredMethod("getService", String.class);
            Object binderObject = getService.invoke(null, PANO_SERVICE);

            if (!(binderObject instanceof android.os.IBinder)) {
                logger.warn("IBYDAutoPanoService binder unavailable");
                return false;
            }
            android.os.IBinder binder = (android.os.IBinder) binderObject;

            android.os.Parcel data = android.os.Parcel.obtain();
            android.os.Parcel reply = android.os.Parcel.obtain();
            try {
                data.writeInterfaceToken(PANO_SERVICE);

                // IBYDAutoPanoService transaction 2: setValue(int type, int value)
                data.writeInt(PANO_VIEWPOINT_TYPE);
                data.writeInt(output);

                boolean handled = binder.transact(PANO_SET_VALUE_TRANSACTION, data, reply, 0);
                if (!handled) {
                    logger.warn("IBYDAutoPanoService setValue transact failed");
                    return false;
                }

                reply.readException();
                logger.info("OEM panorama viewpoint output=" + output + " set successfully");
                return true;
            } finally {
                reply.recycle();
                data.recycle();
            }
        } catch (Throwable t) {
            logger.warn("OEM panorama setValue failed: " + t.getMessage());
            return false;
        }
    }
}
