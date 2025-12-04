/*
 * sonic-agent  Agent of Sonic Cloud Real Machine Platform.
 * Copyright (C) 2022 SonicCloudOrg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.cloud.sonic.agent.tests.android.scrcpy;

import com.alibaba.fastjson.JSONObject;
import com.android.ddmlib.IDevice;
import jakarta.websocket.Session;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.common.maps.ScreenMap;
import org.cloud.sonic.agent.tests.android.AndroidTestTaskBootThread;
import org.cloud.sonic.agent.tools.BytesTool;
import org.cloud.sonic.agent.tools.PortTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.BlockingQueue;

/**
 * scrcpy socket线程 (已适配 Scrcpy 3.3 协议)
 * 通过端口转发，将设备视频流转发到此Socket
 */
public class ScrcpyInputSocketThread extends Thread {

    private final Logger log = LoggerFactory.getLogger(ScrcpyInputSocketThread.class);

    public final static String ANDROID_INPUT_SOCKET_PRE = "android-scrcpy-input-socket-task-%s-%s-%s";

    private IDevice iDevice;

    private BlockingQueue<byte[]> dataQueue;

    private ScrcpyLocalThread scrcpyLocalThread;

    private AndroidTestTaskBootThread androidTestTaskBootThread;

    private Session session;

    public ScrcpyInputSocketThread(IDevice iDevice, BlockingQueue<byte[]> dataQueue, ScrcpyLocalThread scrcpyLocalThread, Session session) {
        this.iDevice = iDevice;
        this.dataQueue = dataQueue;
        this.scrcpyLocalThread = scrcpyLocalThread;
        this.session = session;
        this.androidTestTaskBootThread = scrcpyLocalThread.getAndroidTestTaskBootThread();
        this.setDaemon(false);
        this.setName(androidTestTaskBootThread.formatThreadName(ANDROID_INPUT_SOCKET_PRE));
    }

    public IDevice getiDevice() {
        return iDevice;
    }

    public BlockingQueue<byte[]> getDataQueue() {
        return dataQueue;
    }

    public ScrcpyLocalThread getScrcpyLocalThread() {
        return scrcpyLocalThread;
    }

    public AndroidTestTaskBootThread getAndroidTestTaskBootThread() {
        return androidTestTaskBootThread;
    }

    public Session getSession() {
        return session;
    }

    // 不再需要手动分包的大缓存了，Scrcpy 3.3 直接读指定长度
    // private static final int BUFFER_SIZE = 1024 * 1024 * 10;
    // private static final int READ_BUFFER_SIZE = 1024 * 5;

    @Override
    public void run() {
        int scrcpyPort = PortTool.getPort();
        AndroidDeviceBridgeTool.forward(iDevice, scrcpyPort, "scrcpy");
        Socket videoSocket = new Socket();
        InputStream inputStream = null;
        DataInputStream dis = null;

        try {
            videoSocket.connect(new InetSocketAddress("localhost", scrcpyPort));
            inputStream = videoSocket.getInputStream();
            dis = new DataInputStream(inputStream); // 使用 DataInputStream 以便读取 int/long

            if (videoSocket.isConnected()) {
                // =======================================================
                // 1. 适配 Scrcpy 3.3 握手协议 (Handshake) - 必须消费掉这些字节
                // =======================================================

                // Scrcpy 3.3: 64(Name) + 4(Width) + 4(Height) = 72 bytes
                byte[] deviceInfo = new byte[72];
                dis.readFully(deviceInfo);

                // 解析宽高 (大端序)
                ByteBuffer infoBb = ByteBuffer.wrap(deviceInfo);
                infoBb.position(64); // 跳过名字
                int width = infoBb.getInt();
                int height = infoBb.getInt();

                // Scrcpy 3.3: 紧接着发送 4 字节 Codec ID (如 "h264") -> 必须读出来丢掉
                byte[] codecId = new byte[4];
                dis.readFully(codecId);

                log.info("Scrcpy 3.3 Connected! Native Size: {}x{}, Codec: {}", width, height, new String(codecId));

                // 发送给前端初始化 (保留原逻辑，使用 Agent 自身获取的尺寸，或使用刚才读到的 width/height)
                // 这里建议直接用刚才读到的真实宽高，或者保持原样用 ADB 获取的
                String sizeTotal = AndroidDeviceBridgeTool.getScreenSize(iDevice);
                JSONObject size = new JSONObject();
                size.put("msg", "size");
                size.put("width", sizeTotal.split("x")[0]);
                size.put("height", sizeTotal.split("x")[1]);
                BytesTool.sendText(session, size.toJSONString());
            }

            // =======================================================
            // 2. 适配 Scrcpy 3.3 视频流 (Stream Loop)
            //    逻辑：读12字节头 -> 解析长度 -> 读Payload -> 放入队列
            // =======================================================

            byte[] headerBuf = new byte[12]; // 用来读帧头

            while (scrcpyLocalThread.isAlive()) {
                // A. 读取 12 字节帧头 (8字节 PTS + 4字节 Size)
                try {
                    dis.readFully(headerBuf);
                } catch (EOFException e) {
                    break; // 流结束
                }

                ByteBuffer headerBb = ByteBuffer.wrap(headerBuf);
                headerBb.order(ByteOrder.BIG_ENDIAN); // Scrcpy 协议是大端序

                long pts = headerBb.getLong();    // 读取 PTS (Sonic 不需要，丢弃)
                int packetSize = headerBb.getInt(); // 读取数据长度 (这是最关键的！)

                // B. 安全校验 (防止异常包导致 OOM)
                if (packetSize < 0 || packetSize > 20 * 1024 * 1024) {
                    log.error("Invalid packet size: " + packetSize);
                    break;
                }

                // C. 读取真正的 H.264 视频数据 (Payload)
                byte[] frameData = new byte[packetSize];
                dis.readFully(frameData);

                // D. 放入队列 (Output线程会负责发给前端)
                // 此时 frameData 是去掉了 PTS 头的纯 H.264，前端能直接播
                dataQueue.put(frameData);
            }

        } catch (Exception e) {
            log.error("Scrcpy input socket error: ", e);
            e.printStackTrace();
        } finally {
            if (scrcpyLocalThread.isAlive()) {
                scrcpyLocalThread.interrupt();
                log.info("scrcpy thread closed.");
            }
            if (videoSocket.isConnected()) {
                try {
                    videoSocket.close();
                    log.info("scrcpy video socket closed.");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (dis != null) {
                try {
                    dis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        AndroidDeviceBridgeTool.removeForward(iDevice, scrcpyPort, "scrcpy");
        if (session != null) {
            ScreenMap.getMap().remove(session);
        }
    }
}