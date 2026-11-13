package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageButton;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.inbuilt.manager.InbuiltModManager;
import org.levimc.launcher.core.mods.inbuilt.model.ModIds;
import org.levimc.launcher.core.mods.inbuilt.nativemod.GyroMod;

public class GyroOverlay extends BaseOverlayButton implements SensorEventListener {
    private boolean isActive = false;
    private boolean initialized = false;
    private boolean runtimeActive = true;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private int sensorType;

    private final float[] referenceRotationMatrix = new float[9];
    private final float[] inverseReferenceMatrix = new float[9];
    private boolean hasReference = false;

    private final float[] currentRotationMatrix = new float[9];
    private final float[] deltaRotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];

    private float prevDeltaYaw = 0f;
    private float prevDeltaPitch = 0f;
    private long lastGyroTimestampNs = 0L;

    public GyroOverlay(Activity activity) {
        super(activity);
        sensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        selectBestSensor();
    }

    private void selectBestSensor() {
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotationSensor != null) {
            sensorType = Sensor.TYPE_GAME_ROTATION_VECTOR;
            return;
        }

        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotationSensor != null) {
            sensorType = Sensor.TYPE_ROTATION_VECTOR;
            return;
        }

        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (rotationSensor != null) {
            sensorType = Sensor.TYPE_GYROSCOPE;
            return;
        }
    }

    @Override
    protected String getModId() {
        return ModIds.GYRO;
    }

    @Override
    protected int getIconResource() {
        return isActive ? R.drawable.ic_gyro_pressed : R.drawable.ic_gyro_normal;
    }

    @Override
    public void show(int startX, int startY) {
        if (!initialized) {
            initializeNative();
        }
        super.show(startX, startY);
    }

    public void initializeForKeyboard() {
        if (!initialized) {
            initializeNative();
        }
    }

    private void initializeNative() {
        handler.postDelayed(this::ensureInitialized, 1000);
    }

    private boolean ensureInitialized() {
        if (!initialized && GyroMod.init()) {
            initialized = true;
            applyGyroSettings();
        }
        return initialized;
    }

    private void applyGyroSettings() {
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        float multiplier = manager.getGyroSensitivityMultiplier() / 100f;
        float sensX = manager.getGyroSensitivityX() / 100f * multiplier;
        float sensY = manager.getGyroSensitivityY() / 100f * multiplier;
        boolean invertX = manager.isGyroInvertX();
        boolean invertY = manager.isGyroInvertY();
        float filterDegrees = manager.getGyroDeadzone() / 100f;
        float deadzoneRad = (float) Math.toRadians(filterDegrees);

        GyroMod.nativeSetSensitivityX(sensX);
        GyroMod.nativeSetSensitivityY(sensY);
        GyroMod.nativeSetInvertX(invertX);
        GyroMod.nativeSetInvertY(invertY);
        GyroMod.nativeSetDeadzone(deadzoneRad);
    }

    @Override
    protected void onButtonClick() {
        if (!initialized) {
            return;
        }

        if (rotationSensor == null) {
            return;
        }

        if (isActive) {
            disableGyro();
        } else {
            enableGyro();
        }
    }

    @Override
    protected void onButtonPressEnd() {
    }

    private void enableGyro() {
        isActive = true;
        hasReference = false;
        prevDeltaYaw = 0f;
        prevDeltaPitch = 0f;
        lastGyroTimestampNs = 0L;

        if (runtimeActive) sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        GyroMod.nativeSetEnabled(true);
        updateButtonState(true);
    }

    private void disableGyro() {
        isActive = false;
        hasReference = false;

        sensorManager.unregisterListener(this);
        GyroMod.nativeSetEnabled(false);
        updateButtonState(false);
    }

    public void setRuntimeActive(boolean active) {
        if (runtimeActive == active) return;
        runtimeActive = active;
        sensorManager.unregisterListener(this);
        calibrate();
        if (active && isActive && rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    private void calibrate() {
        hasReference = false;
        prevDeltaYaw = 0f;
        prevDeltaPitch = 0f;
        lastGyroTimestampNs = 0L;
    }

    private void updateButtonState(boolean active) {
        if (overlayView instanceof ImageButton) {
            ImageButton btn = (ImageButton) overlayView;
            float userOpacity = getButtonOpacity();
            btn.setAlpha(userOpacity);
            btn.setImageResource(active ? R.drawable.ic_gyro_pressed : R.drawable.ic_gyro_normal);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!runtimeActive || !isActive || !initialized) return;

        if (sensorType == Sensor.TYPE_GYROSCOPE) {
            handleGyroscopeEvent(event);
        } else {
            handleRotationVectorEvent(event);
        }
    }

    private void handleRotationVectorEvent(SensorEvent event) {
        SensorManager.getRotationMatrixFromVector(currentRotationMatrix, event.values);

        if (!hasReference) {
            System.arraycopy(currentRotationMatrix, 0, referenceRotationMatrix, 0, 9);
            invertMatrix3x3(referenceRotationMatrix, inverseReferenceMatrix);
            hasReference = true;
            return;
        }

        multiplyMatrix3x3(inverseReferenceMatrix, currentRotationMatrix, deltaRotationMatrix);

        float rotX = (deltaRotationMatrix[7] - deltaRotationMatrix[5]) / 2.0f;
        float rotY = (deltaRotationMatrix[2] - deltaRotationMatrix[6]) / 2.0f;
        float rotZ = (deltaRotationMatrix[3] - deltaRotationMatrix[1]) / 2.0f;

        float deltaYaw = 0f;
        float deltaPitch = 0f;

        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        switch (rotation) {
            case android.view.Surface.ROTATION_0:
                deltaPitch = -rotX;
                deltaYaw = rotY;
                break;
            case android.view.Surface.ROTATION_90:
                deltaPitch = -rotY;
                deltaYaw = -rotX;
                break;
            case android.view.Surface.ROTATION_180:
                deltaPitch = rotX;
                deltaYaw = -rotY;
                break;
            case android.view.Surface.ROTATION_270:
                deltaPitch = rotY;
                deltaYaw = rotX;
                break;
        }

        float smoothFactor = 0.5f;
        deltaYaw = smoothFactor * deltaYaw + (1f - smoothFactor) * prevDeltaYaw;
        deltaPitch = smoothFactor * deltaPitch + (1f - smoothFactor) * prevDeltaPitch;
        prevDeltaYaw = deltaYaw;
        prevDeltaPitch = deltaPitch;

        System.arraycopy(currentRotationMatrix, 0, referenceRotationMatrix, 0, 9);
        invertMatrix3x3(referenceRotationMatrix, inverseReferenceMatrix);

        GyroMod.nativeUpdateDelta(deltaYaw, deltaPitch);
    }

    private void handleGyroscopeEvent(SensorEvent event) {
        if (lastGyroTimestampNs == 0L) {
            lastGyroTimestampNs = event.timestamp;
            return;
        }

        float dt = (event.timestamp - lastGyroTimestampNs) * 1.0e-9f;
        lastGyroTimestampNs = event.timestamp;
        if (dt <= 0f) {
            return;
        }
        dt = Math.min(dt, 0.05f);

        float gyroX = event.values[0];
        float gyroY = event.values[1];
        float gyroZ = event.values[2];

        float deltaYaw = 0f;
        float deltaPitch = 0f;

        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        switch (rotation) {
            case android.view.Surface.ROTATION_0:
                deltaPitch = -gyroX * dt;
                deltaYaw = gyroY * dt;
                break;
            case android.view.Surface.ROTATION_90:
                deltaPitch = -gyroY * dt;
                deltaYaw = -gyroX * dt;
                break;
            case android.view.Surface.ROTATION_180:
                deltaPitch = gyroX * dt;
                deltaYaw = -gyroY * dt;
                break;
            case android.view.Surface.ROTATION_270:
                deltaPitch = gyroY * dt;
                deltaYaw = gyroX * dt;
                break;
        }

        GyroMod.nativeUpdateDelta(deltaYaw, deltaPitch);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed
    }

    @Override
    public void hide() {
        if (isActive && initialized) {
            disableGyro();
        }
        super.hide();
    }

    @Override
    public void applyConfigurationChanges() {
        super.applyConfigurationChanges();
        if (initialized) {
            applyGyroSettings();
        }
    }

    public boolean isActive() {
        return isActive;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void toggleGyro() {
        if (rotationSensor == null || !ensureInitialized()) return;
        if (isActive) {
            disableGyro();
        } else {
            enableGyro();
        }
    }

    private static void invertMatrix3x3(float[] m, float[] out) {
        out[0] = m[0]; out[1] = m[3]; out[2] = m[6];
        out[3] = m[1]; out[4] = m[4]; out[5] = m[7];
        out[6] = m[2]; out[7] = m[5]; out[8] = m[8];
    }

    private static void multiplyMatrix3x3(float[] a, float[] b, float[] result) {
        result[0] = a[0]*b[0] + a[1]*b[3] + a[2]*b[6];
        result[1] = a[0]*b[1] + a[1]*b[4] + a[2]*b[7];
        result[2] = a[0]*b[2] + a[1]*b[5] + a[2]*b[8];

        result[3] = a[3]*b[0] + a[4]*b[3] + a[5]*b[6];
        result[4] = a[3]*b[1] + a[4]*b[4] + a[5]*b[7];
        result[5] = a[3]*b[2] + a[4]*b[5] + a[5]*b[8];

        result[6] = a[6]*b[0] + a[7]*b[3] + a[8]*b[6];
        result[7] = a[6]*b[1] + a[7]*b[4] + a[8]*b[7];
        result[8] = a[6]*b[2] + a[7]*b[5] + a[8]*b[8];
    }
}
