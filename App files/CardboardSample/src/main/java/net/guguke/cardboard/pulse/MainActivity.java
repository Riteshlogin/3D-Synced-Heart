/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.guguke.cardboard.pulse;

import com.google.vrtoolkit.cardboard.CardboardActivity;
import com.google.vrtoolkit.cardboard.CardboardView;
import com.google.vrtoolkit.cardboard.Eye;
import com.google.vrtoolkit.cardboard.HeadTransform;
import com.google.vrtoolkit.cardboard.Viewport;
import com.google.vrtoolkit.cardboard.audio.CardboardAudioEngine;
import com.adafruit.bleuart.BluetoothLeUart;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.opengl.GLES10;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * A Cardboard sample application.
 */
public class MainActivity extends CardboardActivity implements CardboardView.StereoRenderer,
        BluetoothLeUart.Callback {
  private static final String TAG = "MainActivity";

  private BluetoothLeUart uart;

  private static final float Z_NEAR = 0.1f;
  private static final float Z_FAR = 100.0f;

  private static final float CAMERA_Z = 0.01f;
  private static final float TIME_DELTA = 1.0f;

  private static final float YAW_LIMIT = 0.12f;
  private static final float PITCH_LIMIT = 0.12f;

  private static final int COORDS_PER_VERTEX = 3;

  // We keep the light always position just above the user.
  private static final float[] LIGHT_POS_IN_WORLD_SPACE = new float[] {0.0f, 2.0f, 0.0f, 1.0f};

  private static final float MODEL_DISTANCE = 2.0f;

  private static final String SOUND_FILE = "cube_sound.wav";

  private final float[] lightPosInEyeSpace = new float[4];

  private FloatBuffer floorVertices;
  private FloatBuffer floorColors;
  private FloatBuffer floorNormals;

  private FloatBuffer heartVertices;
  private FloatBuffer heartNormals;
  /*private FloatBuffer darkRedColors;
  private FloatBuffer cardinalRedColors;*/

  private int heartProgram;
  private int floorProgram;

  private int heartPositionParam;
  private int heartNormalParam;
  private int heartColorParam;
  private int heartModelParam;
  private int heartModelViewParam;
  private int heartModelViewProjectionParam;
  private int heartLightPosParam;

  private int floorPositionParam;
  private int floorNormalParam;
  private int floorColorParam;
  private int floorModelParam;
  private int floorModelViewParam;
  private int floorModelViewProjectionParam;
  private int floorLightPosParam;

  private float[] modelHeart;
  private float[] camera;
  private float[] view;
  private float[] headView;
  private float[] modelViewProjection;
  private float[] modelView;
  private float[] modelFloor;

  private float[] modelPosition;
  private float[] headRotation;

  private int signalFrame = 0;
  private FloatBuffer heartColors;
  private float mHeartPrevScale = 1.0f;

  private int score = 0;
  private float objectDistance = MODEL_DISTANCE;
  private float floorDepth = 20f;

  private Vibrator vibrator;
  private CardboardOverlayView overlayView;

  private CardboardAudioEngine cardboardAudioEngine;
  private volatile int soundId = CardboardAudioEngine.INVALID_ID;

  /**
   * Converts a raw text file, saved as a resource, into an OpenGL ES shader.
   *
   * @param type The type of shader we will be creating.
   * @param resId The resource ID of the raw text file about to be turned into a shader.
   * @return The shader object handler.
   */
  private int loadGLShader(int type, int resId) {
    String code = readRawTextFile(resId);
    int shader = GLES20.glCreateShader(type);
    GLES20.glShaderSource(shader, code);
    GLES20.glCompileShader(shader);

    // Get the compilation status.
    final int[] compileStatus = new int[1];
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);

    // If the compilation failed, delete the shader.
    if (compileStatus[0] == 0) {
      Log.e(TAG, "Error compiling shader: " + GLES20.glGetShaderInfoLog(shader));
      GLES20.glDeleteShader(shader);
      shader = 0;
    }

    if (shader == 0) {
      throw new RuntimeException("Error creating shader.");
    }

    return shader;
  }

  /**
   * Checks if we've had an error inside of OpenGL ES, and if so what that error is.
   *
   * @param label Label to report in case of error.
   */
  private static void checkGLError(String label) {
    int error;
    while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
      Log.e(TAG, label + ": glError " + error);
      throw new RuntimeException(label + ": glError " + error);
    }
  }

  /**
   * Sets the view to our CardboardView and initializes the transformation matrices we will use
   * to render our scene.
   */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    uart = new BluetoothLeUart(getApplicationContext());

    setContentView(R.layout.common_ui);
    CardboardView cardboardView = (CardboardView) findViewById(R.id.cardboard_view);
    cardboardView.setRestoreGLStateEnabled(false);
    cardboardView.setRenderer(this);
    setCardboardView(cardboardView);

    modelHeart = new float[16];
    camera = new float[16];
    view = new float[16];
    modelViewProjection = new float[16];
    modelView = new float[16];
    modelFloor = new float[16];
    // Model first appears directly in front of user.
    modelPosition = new float[] {0.0f, 0.0f, -MODEL_DISTANCE};
    headRotation = new float[4];
    headView = new float[16];
    vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

    overlayView = (CardboardOverlayView) findViewById(R.id.overlay);
    overlayView.show3DToast("Pull the magnet when you find an object.");

    // Initialize 3D audio engine.
    cardboardAudioEngine =
        new CardboardAudioEngine(getAssets(), CardboardAudioEngine.RenderingQuality.HIGH);
  }

  @Override
  public void onPause() {
    cardboardAudioEngine.pause();
    super.onPause();
  }

  @Override
  public void onResume() {
    super.onResume();
    cardboardAudioEngine.resume();
    uart.registerCallback(this);
    uart.connectFirstAvailable();
  }

  @Override
  public void onRendererShutdown() {
    Log.i(TAG, "onRendererShutdown");
  }

  @Override
  public void onSurfaceChanged(int width, int height) {
    Log.i(TAG, "onSurfaceChanged");
  }

  /**
   * Creates the buffers we use to store information about the 3D world.
   *
   * <p>OpenGL doesn't use Java arrays, but rather needs data in a format it can understand.
   * Hence we use ByteBuffers.
   *
   * @param config The EGL configuration used when creating the surface.
   */
  @Override
  public void onSurfaceCreated(EGLConfig config) {
    Log.i(TAG, "onSurfaceCreated");
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 0.5f); // Dark background so text shows up well.

    ByteBuffer bbVertices = ByteBuffer.allocateDirect(WorldLayoutData.HEART_COORDS.length * 4);
    bbVertices.order(ByteOrder.nativeOrder());
    heartVertices = bbVertices.asFloatBuffer();
    heartVertices.put(WorldLayoutData.HEART_COORDS);
    heartVertices.position(0);

    /*ByteBuffer bbcColor = ByteBuffer.allocateDirect(240 * WorldLayoutData.CARDINAL_RED_COLOR.length * 4);
    bbcColor.order(ByteOrder.nativeOrder());
    cardinalRedColors = bbcColor.asFloatBuffer();
    for (int i = 0; i < 240; i++) cardinalRedColors.put(WorldLayoutData.CARDINAL_RED_COLOR);
    cardinalRedColors.position(0);

    ByteBuffer bbdColor = ByteBuffer.allocateDirect(240 * WorldLayoutData.DARK_RED_COLOR.length * 4);
    bbdColor.order(ByteOrder.nativeOrder());
    darkRedColors = bbdColor.asFloatBuffer();
    for (int i = 0; i < 240; i++) darkRedColors.put(WorldLayoutData.DARK_RED_COLOR);
    darkRedColors.position(0);*/

    ByteBuffer bbNormals = ByteBuffer.allocateDirect(WorldLayoutData.HEART_NORMALS.length * 4);
    bbNormals.order(ByteOrder.nativeOrder());
    heartNormals = bbNormals.asFloatBuffer();
    heartNormals.put(WorldLayoutData.HEART_NORMALS);
    heartNormals.position(0);

    // make a floor
    ByteBuffer bbFloorVertices = ByteBuffer.allocateDirect(WorldLayoutData.FLOOR_COORDS.length * 4);
    bbFloorVertices.order(ByteOrder.nativeOrder());
    floorVertices = bbFloorVertices.asFloatBuffer();
    floorVertices.put(WorldLayoutData.FLOOR_COORDS);
    floorVertices.position(0);

    ByteBuffer bbFloorNormals = ByteBuffer.allocateDirect(WorldLayoutData.FLOOR_NORMALS.length * 4);
    bbFloorNormals.order(ByteOrder.nativeOrder());
    floorNormals = bbFloorNormals.asFloatBuffer();
    floorNormals.put(WorldLayoutData.FLOOR_NORMALS);
    floorNormals.position(0);

    ByteBuffer bbFloorColors = ByteBuffer.allocateDirect(WorldLayoutData.FLOOR_COLORS.length * 4);
    bbFloorColors.order(ByteOrder.nativeOrder());
    floorColors = bbFloorColors.asFloatBuffer();
    floorColors.put(WorldLayoutData.FLOOR_COLORS);
    floorColors.position(0);

    int vertexShader = loadGLShader(GLES20.GL_VERTEX_SHADER, R.raw.light_vertex);
    int gridShader = loadGLShader(GLES20.GL_FRAGMENT_SHADER, R.raw.grid_fragment);
    int passthroughShader = loadGLShader(GLES20.GL_FRAGMENT_SHADER, R.raw.passthrough_fragment);

    heartProgram = GLES20.glCreateProgram();
    GLES20.glAttachShader(heartProgram, vertexShader);
    GLES20.glAttachShader(heartProgram, passthroughShader);
    GLES20.glLinkProgram(heartProgram);
    GLES20.glUseProgram(heartProgram);

    checkGLError("Heart program");

    heartPositionParam = GLES20.glGetAttribLocation(heartProgram, "a_Position");
    heartNormalParam = GLES20.glGetAttribLocation(heartProgram, "a_Normal");
    heartColorParam = GLES20.glGetAttribLocation(heartProgram, "a_Color");

    heartModelParam = GLES20.glGetUniformLocation(heartProgram, "u_Model");
    heartModelViewParam = GLES20.glGetUniformLocation(heartProgram, "u_MVMatrix");
    heartModelViewProjectionParam = GLES20.glGetUniformLocation(heartProgram, "u_MVP");
    heartLightPosParam = GLES20.glGetUniformLocation(heartProgram, "u_LightPos");

    GLES20.glEnableVertexAttribArray(heartPositionParam);
    GLES20.glEnableVertexAttribArray(heartNormalParam);
    GLES20.glEnableVertexAttribArray(heartColorParam);

    checkGLError("Heart program params");

    floorProgram = GLES20.glCreateProgram();
    GLES20.glAttachShader(floorProgram, vertexShader);
    GLES20.glAttachShader(floorProgram, gridShader);
    GLES20.glLinkProgram(floorProgram);
    GLES20.glUseProgram(floorProgram);

    checkGLError("Floor program");

    floorModelParam = GLES20.glGetUniformLocation(floorProgram, "u_Model");
    floorModelViewParam = GLES20.glGetUniformLocation(floorProgram, "u_MVMatrix");
    floorModelViewProjectionParam = GLES20.glGetUniformLocation(floorProgram, "u_MVP");
    floorLightPosParam = GLES20.glGetUniformLocation(floorProgram, "u_LightPos");

    floorPositionParam = GLES20.glGetAttribLocation(floorProgram, "a_Position");
    floorNormalParam = GLES20.glGetAttribLocation(floorProgram, "a_Normal");
    floorColorParam = GLES20.glGetAttribLocation(floorProgram, "a_Color");

    GLES20.glEnableVertexAttribArray(floorPositionParam);
    GLES20.glEnableVertexAttribArray(floorNormalParam);
    GLES20.glEnableVertexAttribArray(floorColorParam);

    checkGLError("Floor program params");

    Matrix.setIdentityM(modelFloor, 0);
    Matrix.translateM(modelFloor, 0, 0, -floorDepth, 0); // Floor appears below user.

    // Avoid any delays during start-up due to decoding of sound files.
    new Thread(
            new Runnable() {
              public void run() {
                // Start spatial audio playback of SOUND_FILE at the model postion. The returned
                //soundId handle is stored and allows for repositioning the sound object whenever
                // the cube position changes.
                cardboardAudioEngine.preloadSoundFile(SOUND_FILE);
                soundId = cardboardAudioEngine.createSoundObject(SOUND_FILE);
                cardboardAudioEngine.setSoundObjectPosition(
                    soundId, modelPosition[0], modelPosition[1], modelPosition[2]);
                cardboardAudioEngine.playSound(soundId, true /* looped playback */);
              }
            })
        .start();

    updateModelPosition();

    checkGLError("onSurfaceCreated");
  }

  /**
   * Updates the cube model position.
   */
  private void updateModelPosition() {
    Matrix.setIdentityM(modelHeart, 0);
    Matrix.translateM(modelHeart, 0, modelPosition[0], modelPosition[1], modelPosition[2]);

    // Update the sound location to match it with the new cube position.
    if (soundId != CardboardAudioEngine.INVALID_ID) {
      cardboardAudioEngine.setSoundObjectPosition(
          soundId, modelPosition[0], modelPosition[1], modelPosition[2]);
    }
    checkGLError("updateCubePosition");
  }

  /**
   * Converts a raw text file into a string.
   *
   * @param resId The resource ID of the raw text file about to be turned into a shader.
   * @return The context of the text file, or null in case of error.
   */
  private String readRawTextFile(int resId) {
    InputStream inputStream = getResources().openRawResource(resId);
    try {
      BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line).append("\n");
      }
      reader.close();
      return sb.toString();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return null;
  }

  /**
   * Prepares OpenGL ES before we draw a frame.
   *
   * @param headTransform The head transformation in the new frame.
   */
  @Override
  public void onNewFrame(HeadTransform headTransform) {
    // Build the Model part of the ModelView matrix.
    Matrix.rotateM(modelHeart, 0, TIME_DELTA, 0.0f, 0.5f, 0.0f);
    float mHeartNewScale = 1.0f + (float) (Math.cos(Math.PI + signalFrame / 30.0 * Math.PI) + 1.0f) / 6;
    Matrix.scaleM(modelHeart, 0, mHeartNewScale / mHeartPrevScale, mHeartNewScale / mHeartPrevScale, 1f);
    mHeartPrevScale = mHeartNewScale;
    // Build the camera matrix and apply it to the ModelView.
    Matrix.setLookAtM(camera, 0, 0.0f, 0.0f, CAMERA_Z, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);

    headTransform.getHeadView(headView, 0);

    // Update the 3d audio engine with the most recent head rotation.
    headTransform.getQuaternion(headRotation, 0);
    cardboardAudioEngine.setHeadRotation(
            headRotation[0], headRotation[1], headRotation[2], headRotation[3]);

    checkGLError("onReadyToDraw");
  }

  /**
   * Draws a frame for an eye.
   *
   * @param eye The eye to render. Includes all required transformations.
   */
  @Override
  public void onDrawEye(Eye eye) {
    GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    checkGLError("colorParam");

    // Apply the eye transformation to the camera.
    Matrix.multiplyMM(view, 0, eye.getEyeView(), 0, camera, 0);

    // Set the position of the light
    Matrix.multiplyMV(lightPosInEyeSpace, 0, view, 0, LIGHT_POS_IN_WORLD_SPACE, 0);

    // Build the ModelView and ModelViewProjection matrices
    // for calculating cube position and light.
    float[] perspective = eye.getPerspective(Z_NEAR, Z_FAR);
    Matrix.multiplyMM(modelView, 0, view, 0, modelHeart, 0);
    Matrix.multiplyMM(modelViewProjection, 0, perspective, 0, modelView, 0);
    drawHeart();

    // Set modelView for the floor, so we draw floor in the correct location
    Matrix.multiplyMM(modelView, 0, view, 0, modelFloor, 0);
    Matrix.multiplyMM(modelViewProjection, 0, perspective, 0, modelView, 0);
    drawFloor();
  }

  @Override
  public void onFinishFrame(Viewport viewport) {}

  private FloatBuffer getHeartColors() {
    signalFrame = (++signalFrame) % 60;
    float proportion = (float) (Math.cos(Math.PI + signalFrame / 30.0 * Math.PI) + 1.0f) / 2;
    float[] mixedColor = new float[] {
            WorldLayoutData.CARDINAL_RED_COLOR[0] * proportion + WorldLayoutData.DARK_RED_COLOR[0] * (1.0f - proportion),
            WorldLayoutData.CARDINAL_RED_COLOR[1] * proportion + WorldLayoutData.DARK_RED_COLOR[1] * (1.0f - proportion),
            WorldLayoutData.CARDINAL_RED_COLOR[2] * proportion + WorldLayoutData.DARK_RED_COLOR[2] * (1.0f - proportion),
            WorldLayoutData.CARDINAL_RED_COLOR[3] * proportion + WorldLayoutData.DARK_RED_COLOR[3] * (1.0f - proportion)
    };
    ByteBuffer bbmColor = ByteBuffer.allocateDirect(240 * WorldLayoutData.CARDINAL_RED_COLOR.length * 4);
    bbmColor.order(ByteOrder.nativeOrder());
    heartColors = bbmColor.asFloatBuffer();
    for (int i = 0; i < 240; i++) heartColors.put(mixedColor);
    heartColors.position(0);
    return heartColors;
  }
  /**
   * Draw the cube.
   *
   * <p>We've set all of our transformation matrices. Now we simply pass them into the shadFler.
   */
  public void drawHeart() {
    GLES20.glUseProgram(heartProgram);

    GLES20.glClearDepthf(1.0f);

    GLES20.glUniform3fv(heartLightPosParam, 1, lightPosInEyeSpace, 0);

    // Set the Model in the shader, used to calculate lighting
    GLES20.glUniformMatrix4fv(heartModelParam, 1, false, modelHeart, 0);

    // Set the ModelView in the shader, used to calculate lighting
    GLES20.glUniformMatrix4fv(heartModelViewParam, 1, false, modelView, 0);

    // Set the position of the cube
    GLES20.glVertexAttribPointer(
            heartPositionParam, COORDS_PER_VERTEX, GLES20.GL_FLOAT, true, 0, heartVertices);

    // Set the ModelViewProjection matrix in the shader.
    GLES20.glUniformMatrix4fv(heartModelViewProjectionParam, 1, false, modelViewProjection, 0);

    // Set the normal positions of the cube, again for shading
    GLES20.glVertexAttribPointer(heartNormalParam, 3, GLES20.GL_FLOAT, false, 0, heartNormals);
    //GLES20.glVertexAttrib4fv(heartColorParam, darkRedColor);
    GLES20.glVertexAttribPointer(heartColorParam, 4, GLES20.GL_FLOAT, false, 0,
        getHeartColors());

    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 240);
    checkGLError("Drawing heart");
  }

  /**
   * Draw the floor.
   *
   * <p>This feeds in data for the floor into the shader. Note that this doesn't feed in data about
   * position of the light, so if we rewrite our code to draw the floor first, the lighting might
   * look strange.
   */
  public void drawFloor() {
    GLES20.glUseProgram(floorProgram);

    // Set ModelView, MVP, position, normals, and color.
    GLES20.glUniform3fv(floorLightPosParam, 1, lightPosInEyeSpace, 0);
    GLES20.glUniformMatrix4fv(floorModelParam, 1, false, modelFloor, 0);
    GLES20.glUniformMatrix4fv(floorModelViewParam, 1, false, modelView, 0);
    GLES20.glUniformMatrix4fv(floorModelViewProjectionParam, 1, false, modelViewProjection, 0);
    GLES20.glVertexAttribPointer(
        floorPositionParam, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, floorVertices);
    GLES20.glVertexAttribPointer(floorNormalParam, 3, GLES20.GL_FLOAT, false, 0, floorNormals);
    GLES20.glVertexAttribPointer(floorColorParam, 4, GLES20.GL_FLOAT, false, 0, floorColors);

    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);

    checkGLError("drawing floor");
  }

  /**
   * Called when the Cardboard trigger is pulled.
   */
  @Override
  public void onCardboardTrigger() {
    Log.i(TAG, "onCardboardTrigger");

    /*if (isLookingAtObject()) {
      score++;
      overlayView.show3DToast("Found it! Look around for another one.\nScore = " + score);
      hideObject();
    } else {
      overlayView.show3DToast("Look around to find the object!");
    }*/

    // Always give user feedback.
    vibrator.vibrate(50);
  }

  /**
   * Find a new random position for the object.
   *
   * <p>We'll rotate it around the Y-axis so it's out of sight, and then up or down by a little bit.
   */
  /*private void hideObject() {
    float[] rotationMatrix = new float[16];
    float[] posVec = new float[4];

    // First rotate in XZ plane, between 90 and 270 deg away, and scale so that we vary
    // the object's distance from the user.
    float angleXZ = (float) Math.random() * 180 + 90;
    Matrix.setRotateM(rotationMatrix, 0, angleXZ, 0f, 1f, 0f);
    float oldObjectDistance = objectDistance;
    objectDistance =
        (float) Math.random() * (MAX_MODEL_DISTANCE - MIN_MODEL_DISTANCE) + MIN_MODEL_DISTANCE;
    float objectScalingFactor = objectDistance / oldObjectDistance;
    Matrix.scaleM(rotationMatrix, 0, objectScalingFactor, objectScalingFactor, objectScalingFactor);
    Matrix.multiplyMV(posVec, 0, rotationMatrix, 0, modelHeart, 12);

    float angleY = (float) Math.random() * 80 - 40; // Angle in Y plane, between -40 and 40.
    angleY = (float) Math.toRadians(angleY);
    float newY = (float) Math.tan(angleY) * objectDistance;

    modelPosition[0] = posVec[0];
    modelPosition[1] = newY;
    modelPosition[2] = posVec[2];

    updateModelPosition();
  }*/

  /**
   * Check if user is looking at object by calculating where the object is in eye-space.
   *
   * @return true if the user is looking at the object.
   */
  private boolean isLookingAtObject() {
    float[] initVec = {0, 0, 0, 1.0f};
    float[] objPositionVec = new float[4];

    // Convert object space to camera space. Use the headView from onNewFrame.
    Matrix.multiplyMM(modelView, 0, headView, 0, modelHeart, 0);
    Matrix.multiplyMV(objPositionVec, 0, modelView, 0, initVec, 0);

    float pitch = (float) Math.atan2(objPositionVec[1], -objPositionVec[2]);
    float yaw = (float) Math.atan2(objPositionVec[0], -objPositionVec[2]);

    return Math.abs(pitch) < PITCH_LIMIT && Math.abs(yaw) < YAW_LIMIT;
  }

  @Override
  public void onConnected(BluetoothLeUart uart) {
    Log.v("BT", "Connected to: " + uart.getDeviceInfo());
  }

  @Override
  protected void onStop() {
    super.onStop();
    uart.unregisterCallback(this);
    uart.disconnect();
  }

  @Override
  public void onConnectFailed(BluetoothLeUart uart) {
    Log.v("BT", "Error connecting to device! " + uart.getDeviceInfo());
  }

  @Override
  public void onDisconnected(BluetoothLeUart uart) {
    Log.v("BT", "Disconnected: " + uart.getDeviceInfo());
  }

  @Override
  public void onReceive(BluetoothLeUart uart, BluetoothGattCharacteristic rx) {
    Log.v("BT", "Received: " + rx.getStringValue(0));
  }

  @Override
  public void onDeviceFound(BluetoothDevice device) {
    Log.v("BT", "Device Found: " + uart.getDeviceInfo());
  }

  @Override
  public void onDeviceInfoAvailable() {
    Log.v("BT", uart.getDeviceInfo());
  }
}
