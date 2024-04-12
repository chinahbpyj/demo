package com.tvos.androidmirror.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;

@SuppressLint("SimpleDateFormat")
public class CommonHelper {

	public static void int2ByteL(byte[] targets, int res) {
		targets[0] = (byte) (res & 0xff);// 最低位
		targets[1] = (byte) ((res >> 8) & 0xff);// 次低位
		targets[2] = (byte) ((res >> 16) & 0xff);// 次高位
		targets[3] = (byte) (res >>> 24);// 最高位,无符号右移。
	}

	public static void int2ByteH(byte[] targets, int res) {
		targets[3] = (byte) (res & 0xff);// 最高位
		targets[2] = (byte) ((res >> 8) & 0xff);
		targets[1] = (byte) ((res >> 16) & 0xff);
		targets[0] = (byte) (res >>> 24);
	}
	
	public static String currentTime() {
		SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS");
		Date curDate = new Date(System.currentTimeMillis());// 获取当前时间
		String str = formatter.format(curDate);
		return str;
	}
	
	/*
	 * 得到的值是一个0到-100的区间值，是一个int型数据，其中0到-50表示信号最好，
	 * -50到-70表示信号偏差，小于-70表示最差，有可能连接不上或者掉线，一般Wifi已断则值为-200
	 */
	public static int getWifiRssi(Context context) {
		WifiInfo wifiInfo = ((WifiManager) context.getSystemService(Context.WIFI_SERVICE)).getConnectionInfo();
		return wifiInfo.getRssi();
	}
	
	/**
	 * @return 0|less latency, 1|fluency
	 */
	public static int getProp() {
		String mode = get(MIRROR_MODE_PROPERTY, "0");
		LOG.d(MIRROR_MODE_PROPERTY, mode);
		return Integer.parseInt(mode);
	}
	
	private static final String MIRROR_MODE_PROPERTY = "mirror_mode";
	private static volatile Method get = null;

	private static String get(String prop, String defaultvalue) {
		String value = defaultvalue;
		try {
			if (null == get) {
				synchronized (CommonHelper.class) {
					if (null == get) {
						Class<?> cls = Class.forName("android.os.SystemProperties");
						get = cls.getDeclaredMethod("get", new Class<?>[] { String.class, String.class });
					}
				}
			}
			value = (String) (get.invoke(null, new Object[] { prop, defaultvalue }));
		} catch (Throwable e) {
			e.printStackTrace();
		}
		return value;
	}
}
