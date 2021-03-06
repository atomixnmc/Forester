/*
 * Copyright (c) 2011, Andreas Olofsson
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions 
 * are met:
 * 
 * Redistributions of source code must retain the above copyright notice, 
 * this list of conditions and the following disclaimer.
 * 
 * Redistributions in binary form must reproduce the above copyright notice, 
 * this list of conditions and the following disclaimer in the documentation 
 * and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS 
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED 
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR 
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR 
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, 
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, 
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; 
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, 
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR 
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF 
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 */
package forester.trees.impostors;

import com.jme3.asset.AssetManager;
import com.jme3.bounding.BoundingSphere;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue.Bucket;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image.Format;
import com.jme3.util.BufferUtils;
import forester.Forester;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * This class is used for rendering impostor textures. Not finished.
 * 
 * @author Andreas
 */
public class ImpostorTextureGenerator {

    protected static ImpostorTextureGenerator instance;
    protected static int TEXTURE_RES = 256;
    protected static int numAngles = 8;
    protected static String textureFolder = "Textures/Impostors/";
    
    protected AssetManager assetManager;
    protected Camera RTTCam;
    protected ViewPort offView;
    protected RenderManager manager;
    protected Renderer renderer;
    protected FrameBuffer offBuffer;
    protected ByteBuffer IMGBuf;
    protected String normalMapName = "NormalMap";
    protected String colorMapName = "DiffuseMap";
//    private String colorMapName = "ColorMap";
    
    protected Material dispMat;
    protected Material colorMat;

    protected ImpostorTextureGenerator() {
        renderer = Forester.getInstance().getApp().getRenderer();
        assetManager = Forester.getInstance().getApp().getAssetManager();
        manager = new RenderManager(renderer);
        dispMat = assetManager.loadMaterial("Materials/ImpostorGen/DispNorm.j3m");
        colorMat = assetManager.loadMaterial("Materials/ImpostorGen/Color.j3m");
    }

    synchronized 
    public static ImpostorTextureGenerator getInstance() {
        if (instance == null) {
            instance = new ImpostorTextureGenerator();
        }
        return instance;
    }

    synchronized
    public void generateImpostors(Spatial model, float distance) {

        List<Material> modelMats = new ArrayList<Material>();

        IMGBuf = BufferUtils.createByteBuffer(4 * TEXTURE_RES * TEXTURE_RES * 16);

        /*
         * Preparing the model for rendering. It needs boundingspheres.
         */

        for (int i = 0; i < ((Node) model).getChildren().size(); i++) {
            Geometry geom = (Geometry) ((Node) model).getChild(i);
            modelMats.add(geom.getMaterial());
            geom.getMesh().setBound(new BoundingSphere());
            geom.getMesh().updateBound();
            geom.updateGeometricState();
        }

        model.updateGeometricState();

        BoundingSphere sphere = (BoundingSphere) model.getWorldBound();
        float rad = sphere.getRadius();

        dispMat.setFloat("BSRadius", rad);

        model.setQueueBucket(Bucket.Transparent);

        /*
         * Preparing the Render-to-texture camera. Setting up proper camera
         * frustums for rendering the model. The distance variable is the distance
         * from the camera where impostors are faded in/out. It ensures the proper
         * perspective is maintained at that point - to reduce artifacts during the
         * transitioning process. 
         * 
         * This of course means perspective will be slightly off further away 
         * from the camera, but it is very, very hard to see.
         */
        
        RTTCam = new Camera(TEXTURE_RES, TEXTURE_RES);

        RTTCam.setLocation(sphere.getCenter().add(new Vector3f(0, 0, distance)));
        RTTCam.lookAt(sphere.getCenter(), Vector3f.UNIT_Y);

        //Frustum angle in degrees.
        float frustumAngle = 2 * FastMath.asin(rad / distance) * 180f / FastMath.PI;
        
        // This is mainly for debugging purposes.
        if (frustumAngle > 45f) {
            throw new RuntimeException("The RTT-camera is too close to the model."
                    + " The y-FoV should be < 45 degrees (it is now: " + frustumAngle + "degrees).");
        }
        
        RTTCam.setFrustumPerspective(frustumAngle, 1.0f, distance - rad, distance + rad);
        
        // Setting up the viewport.
        offView = manager.createMainView("RTTView", RTTCam);
        offView.setClearFlags(true, true, true);
        offView.setBackgroundColor(new ColorRGBA(0f, 0f, 0f, 0f));

        // Creating the offscreen framebuffer.
        offBuffer = new FrameBuffer(TEXTURE_RES, TEXTURE_RES, 2);

        offBuffer.setDepthBuffer(Format.Depth);
        offBuffer.setColorBuffer(Format.RGBA8);
        // Render to the offscreen framebuffer.
        offView.setOutputFrameBuffer(offBuffer);
        // Attaching the scene to the viewport.
        offView.attachScene(model);
        model.setLocalRotation(Quaternion.IDENTITY);
        model.setLocalTranslation(Vector3f.ZERO);

        /*
         * Rendering the model. This takes place in several steps.
         */
        
        float baseAngle = FastMath.TWO_PI / (float) numAngles;

        for (int i = 0; i < ((Node) model).getChildren().size(); i++) {
            Geometry geom = (Geometry) ((Node) model).getChild(i);
            Material mat = dispMat.clone();
            mat.setTexture("ColorMap", modelMats.get(i).getTextureParam(colorMapName).getTextureValue());
            mat.setTexture("NormalMap", modelMats.get(i).getTextureParam(normalMapName).getTextureValue());
            geom.setMaterial(mat);
        }

        int counter = 0;

        //Creating an array to store the pixel values in.
        byte[] IMGArray = new byte[IMGBuf.capacity()];

        while (counter < numAngles) {

            int offsetX = counter % 4;
            int offsetY = (counter + 8) / 4;

            float currAng = baseAngle * counter;

            for (int i = 0; i < ((Node) model).getChildren().size(); i++) {
                Geometry geom = (Geometry) ((Node) model).getChild(i);
                geom.getMaterial().setFloat("RotXZ", currAng);
            }
            
            Vector3f vec = getUCVec(currAng).mult(distance);
            vec.y = sphere.getCenter().y;
            RTTCam.setLocation(vec);

            RTTCam.lookAt(sphere.getCenter(), Vector3f.UNIT_Y);
            model.updateGeometricState();

            ByteBuffer outBuf = BufferUtils.createByteBuffer(4 * TEXTURE_RES * TEXTURE_RES);

            // Render the model and capture the framebuffer.
            manager.render(0, true);
            renderer.readFrameBuffer(offBuffer, outBuf);

            // We need to structure the buffers a bit differently to make
            // the final texture into a proper grid of "sub-textures".
            outBuf.clear();
            byte[] bb = new byte[outBuf.capacity()];
            outBuf.get(bb);

            for (int i = 0; i < TEXTURE_RES; i++) {
                int NDPos = 4 * TEXTURE_RES * (offsetX + i * 4 + offsetY * 4 * TEXTURE_RES);
                System.arraycopy(bb, i * 4 * TEXTURE_RES, IMGArray, NDPos, 4 * TEXTURE_RES);
            }
            counter++;
        }

        for (int i = 0; i < ((Node) model).getChildren().size(); i++) {
            Geometry geom = (Geometry) ((Node) model).getChild(i);

            Material mat = colorMat.clone();
            mat.setTexture("ColorMap", modelMats.get(i).getTextureParam(colorMapName).getTextureValue());
            geom.setMaterial(mat);
        }

        counter = 0;

        while (counter < numAngles) {

            int offsetX = counter % 4;
            int offsetY = counter / 4;

            float currAng = baseAngle * counter;
            Vector3f vec = getUCVec(currAng).mult(distance);
            vec.y = sphere.getCenter().y;
            RTTCam.setLocation(vec);

            RTTCam.lookAt(sphere.getCenter(), Vector3f.UNIT_Y);
            model.updateGeometricState();

            ByteBuffer outBuf = BufferUtils.createByteBuffer(4 * TEXTURE_RES * TEXTURE_RES);

            manager.render(0, true);
            renderer.readFrameBuffer(offBuffer, outBuf);

            outBuf.clear();
            byte[] bb = new byte[outBuf.capacity()];
            outBuf.get(bb);

            for (int i = 0; i < TEXTURE_RES; i++) {
                System.arraycopy(bb, i * 4 * TEXTURE_RES, IMGArray, 4 * TEXTURE_RES * (offsetX + i * 4 + offsetY * 4 * TEXTURE_RES), 4 * TEXTURE_RES);
            }
            counter++;
        }

        IMGBuf.put(IMGArray);
    }

    protected static Vector3f getUCVec(float angle) {
        //Using z for position so compensating with pi/2
        return new Vector3f(FastMath.cos(-angle + FastMath.HALF_PI), 0, FastMath.sin(-angle + FastMath.HALF_PI));
    }

    public static void printImage(ByteBuffer imgBuf, float distance) {
        BufferedImage awtImage = new BufferedImage(TEXTURE_RES * 4, TEXTURE_RES * 4, BufferedImage.TYPE_4BYTE_ABGR);
        WritableRaster wr = awtImage.getRaster();
        DataBufferByte db = (DataBufferByte) wr.getDataBuffer();

        byte[] cpuArray = db.getData();

        // copy native memory to java memory
        imgBuf.clear();
        imgBuf.get(cpuArray);
        imgBuf.clear();

        int width = wr.getWidth();
        int height = wr.getHeight();

//         flip the components the way AWT likes them
        for (int y = 0; y < height / 2; y++) {
            for (int x = 0; x < width; x++) {
                int inPtr = (y * width + x) * 4;
                int outPtr = ((height - y - 1) * width + x) * 4;

                byte b1 = cpuArray[inPtr + 0];
                byte g1 = cpuArray[inPtr + 1];
                byte r1 = cpuArray[inPtr + 2];
                byte a1 = cpuArray[inPtr + 3];

                byte b2 = cpuArray[outPtr + 0];
                byte g2 = cpuArray[outPtr + 1];
                byte r2 = cpuArray[outPtr + 2];
                byte a2 = cpuArray[outPtr + 3];

                cpuArray[outPtr + 0] = a1;
                cpuArray[outPtr + 1] = b1;
                cpuArray[outPtr + 2] = g1;
                cpuArray[outPtr + 3] = r1;

                cpuArray[inPtr + 0] = a2;
                cpuArray[inPtr + 1] = b2;
                cpuArray[inPtr + 2] = g2;
                cpuArray[inPtr + 3] = r2;
            }
        }
        try {
            ImageIO.write(awtImage, "png", new File("Assets/" + textureFolder + "Model" + Float.floatToIntBits(distance) + ".png"));
        } catch (IOException ex) {
        }
    }

    public void setColorMapName(String colorMapName) {
        this.colorMapName = colorMapName;
    }

    public void normalMapName(String normalMapName) {
        this.normalMapName = normalMapName;
    }
}
