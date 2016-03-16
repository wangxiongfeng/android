package com.example.asyncimageloader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.util.Calendar;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;

import android.app.TimePickerDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Message;
import android.support.v4.util.LruCache;
import android.telephony.ServiceState;
import android.widget.TimePicker;

public class ImageDownLoader {
	/**
	 * ����Image���࣬���洢Image�Ĵ�С����LruCache�趨��ֵ��ϵͳ�Զ��ͷ��ڴ�
	 */
	private LruCache<String, Bitmap> mMemoryCache;
	private LruCache<String , Bitmap> mCache;
	/**
	 * �����ļ��������������
	 */
	private FileUtils fileUtils;
	/**
	 * ����Image���̳߳� ExecutorService �������̵߳Ĳ��裺 �̳߳�
	 */
	private ExecutorService mImageThreadPool = null;

	/**
	 * ���ô˳��򻺴�ͼƬ�Ŀռ��С
	 * 
	 * @param context
	 */
	public ImageDownLoader(Context context) {
		// ��ȡϵͳ�����ÿ��Ӧ�ó��������ڴ棬ÿ��Ӧ��ϵͳ����32M
		int maxMemory = (int) Runtime.getRuntime().maxMemory();
		int mCacheSize = maxMemory / 8;
		// ��LruCache����1/8 4M
		mMemoryCache = new LruCache<String, Bitmap>(mCacheSize) {
			// ������д�˷�����������Bitmap�Ĵ�С
			@Override
			protected int sizeOf(String key, Bitmap value) {
				// ��ÿ�δ��뻺���ʱ�����
				return value.getRowBytes() * value.getHeight();
				// return value.getByteCount();
			}
		};
		mCache=new LruCache<String, Bitmap>(mCacheSize){
			protected int sizeOf(String key, Bitmap value) {
				return value.getRowBytes()*value.getHeight();
			};
		};
		fileUtils = new FileUtils(context);
	}

	/**
	 * ��ȡ�̳߳صķ�������Ϊ�漰�����������⣬���Ǽ���ͬ����
	 * 
	 * @return
	 */
	public ExecutorService getThreadPool() {
		if (mImageThreadPool == null) {
			synchronized (ExecutorService.class) {
				if (mImageThreadPool == null) {
					// ��õ�ǰϵͳCPU����Ŀ
					int cpuNums = Runtime.getRuntime().availableProcessors();// ����ʱ���
																				// ���ֻ�й̶���Ŀ�Ļ�̴߳���
					// ��ʱ������µ��߳�Ҫ������ֻ�ܷ�������Ķ����еȴ���ֱ����ǰ���߳���ĳ���߳���ֱֹ�ӱ��Ƴ�����
					// Ϊ������ͼƬ���ӵ���������������2���߳�������ͼƬ
					mImageThreadPool = Executors.newFixedThreadPool(cpuNums);
					// mImageThreadPool = Executors.newCachedThreadPool();
					// //�����ͳ��� �������ں̵ܶ��첽������ 60��
				}
			}
		}
		return mImageThreadPool;

	}

	/**
	 * ���Bitmap���ڴ滺��
	 * 
	 * @param key
	 * @param bitmap
	 */
	public void addBitmapToMemoryCache(String key, Bitmap bitmap) {
		if (getBitmapFromMemCache(key) == null && bitmap != null) {
			mMemoryCache.put(key, bitmap);
		}
	}

	/**
	 * ���ڴ滺���л�ȡһ��Bitmap
	 * 
	 * @param key
	 * @return
	 */
	public Bitmap getBitmapFromMemCache(String key) {
		return mMemoryCache.get(key);
	}

	/**
	 * �ȴ��ڴ滺���л�ȡBitmap,���û�оʹ�SD�������ֻ������л�ȡ��SD�������ֻ����� û�о�ȥ����
	 * 
	 * @param url
	 * @param listener
	 * @return
	 */
	public Bitmap downloadImage(final String url,
			final onImageLoaderListener listener) {
		// �滻Url�з���ĸ�ͷ����ֵ��ַ�������Ƚ���Ҫ����Ϊ������Url��Ϊ�ļ������������ǵ�Url
		// ��Http://xiaanming/abc.jpg;�������ΪͼƬ���ƣ�ϵͳ����ΪxiaanmingΪһ��Ŀ¼��
		// ����û�д�����Ŀ¼�����ļ��ͻᱣ��
		final String subUrl = url.replaceAll("[^\\w]", "");
		// �ж��ڴ����Ƿ��и�ͼƬ(û���ٴ�sdcard�л�ȡ)
		Bitmap bitmap = showCacheBitmap(subUrl);
		if (bitmap != null) {
			return bitmap;
		} else {
			// sdcard���ڴ��ж��޸�ͼƬ
			final Handler handler = new Handler() {
				@Override
				public void handleMessage(Message msg) {
					super.handleMessage(msg);
					listener.onImageLoader((Bitmap) msg.obj, url);
				}
			};
			// �����̳߳ز���
			// mImageThreadPool.execute(new runnable);
			mImageThreadPool = getThreadPool();
			mImageThreadPool.execute(new Runnable() {
				@Override
				public void run() {
					// �������ȡimage
					Bitmap bitmap = getBitmapFormUrl(url);
					Message msg = handler.obtainMessage();
					msg.obj = bitmap;
					handler.sendMessage(msg);
					try {
						// ������SD�������ֻ�Ŀ¼
						fileUtils.savaBitmap(subUrl, bitmap);
					} catch (IOException e) {
						e.printStackTrace();
					}
					// ��Bitmap �����ڴ滺��
					addBitmapToMemoryCache(subUrl, bitmap);
				}
			});
		}
		return null;
	}

	/**
	 * ��ȡBitmap, �ڴ���û�о�ȥ�ֻ�����sd���л�ȡ����һ����getView�л���ã��ȽϹؼ���һ��
	 * 
	 * @param url
	 * @return
	 */
	public Bitmap showCacheBitmap(String url) {
		if (getBitmapFromMemCache(url) != null) {
			return getBitmapFromMemCache(url);
		} else if (fileUtils.isFileExists(url)
				&& fileUtils.getFileSize(url) != 0) {
			// ��SD����ȡ�ֻ������ȡBitmap
			Bitmap bitmap = fileUtils.getBitmap(url);
			// ��Bitmap �����ڴ滺��
			addBitmapToMemoryCache(url, bitmap);
			return bitmap;
		}
		return null;

	}

	/**
	 * �����ȡBitmap
	 * 
	 * @param url
	 * @return
	 */
	private Bitmap getBitmapFormUrl(String url) {
		Bitmap bitmap = null;
		HttpURLConnection con = null;
		try {
			URL mImageUrl = new URL(url);
			con = (HttpURLConnection) mImageUrl.openConnection();
			con.setDoInput(true);
			con.connect();
			InputStream stream = con.getInputStream();
			bitmap = BitmapFactory.decodeStream(stream);
			stream.close();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (con != null) {
				con.disconnect();
			}
		}
//
//		HttpClient client = new DefaultHttpClient();
//		HttpPost post = new HttpPost("");
//		try {
//			HttpResponse httpResponse = client.execute(post);
//			HttpEntity entity=httpResponse.getEntity();
//	     	InputStream inputStream=entity.getContent();
//	     	ByteArrayOutputStream outputStream=new ByteArrayOutputStream();
//	     	byte[] b=new byte[1024];
//	     	int len=0;
//	     	while((inputStream.read(b))!=-1){
//	     		outputStream.write(b, 0, len);
//	     	}
//	     	outputStream.close();
//	     	inputStream.close();
//	        byte[] n=	outputStream.toByteArray();
//	     	String d=new String(b);
//			// httpResponse.getStatusLine().getStatusCode()==200
//		} catch (Exception e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
//		
		// ѹ��ͼƬ
		return bitmap;
	}

	/**
	 * ȡ���������ص�����
	 */
	public synchronized void cancelTask() {
		if (mImageThreadPool != null) {
			mImageThreadPool.shutdownNow();
			mImageThreadPool = null;
		}
	}

	/**
	 * �첽����ͼƬ�Ļص��ӿ�
	 * 
	 * @author len
	 * 
	 */
	public interface onImageLoaderListener {
		void onImageLoader(Bitmap bitmap, String url);
	}

}
