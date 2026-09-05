package com.overdrive.app.camera;

import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;

/**
 * Alternate DiLink 4 camera-direction switch for byd_apa units where the
 * official passive-APA path (GpuSurveillancePipeline.requestPassiveNativeView,
 * which broadcasts android.intent.action.AUTO_VIDEO_BUTTON) does not move the
 * OEM camera. Talks to a DIFFERENT OEM service, {@code IBYDAutoPanoService},
 * directly via a raw Binder transaction.
 *
 * <p>Reverse-engineered by a field user testing this exact unit. NOT verified
 * against the real transaction numbers beyond that user's own testing. Every
 * failure mode here (service absent, transaction rejected, wrong argument
 * shape for this firmware's AIDL revision) degrades to a silent no-op and a
 * warning log, so calling this alongside the official broadcast path is
 * additive and safe — on a unit where the official path already works this
 * call should simply fail to transact.
 *
 * @see com.overdrive.app.byd.NativeCameraViewController
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
