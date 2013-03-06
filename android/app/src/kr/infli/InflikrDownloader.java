//
//  InflikrDownloader
//
//  Copyright 2013 infli.kr mobile
//  https://github.com/eboudrant/inflickr_mobile
//
//  Inspired from
//    http://android-developers.blogspot.in/2010/07/multithreading-for-performance.html
//    https://code.google.com/p/android-imagedownloader/
//

package kr.infli;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.http.AndroidHttpClient;
import android.os.AsyncTask;
import android.os.Handler;
import android.util.Log;
import android.widget.ImageView;

import com.googlecode.flickrjandroid.photos.Photo;

public class InflikrDownloader
{
  static final String LOG_TAG = "InflikrDownloader";

  private Executor m_executor = new ThreadPoolExecutor(1, 5, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(100));
  private Executor m_hiResExecutor = new ThreadPoolExecutor(10, 20, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(100));

  public enum Mode
  {
    NO_ASYNC_TASK, NO_DOWNLOADED_DRAWABLE, CORRECT
  }

  private Mode m_mode = Mode.CORRECT;

  private Context m_context;
  
  public InflikrDownloader(Context context)
  {
    this.m_context = context;
  }

  /**
   * Download the specified image from the Internet and binds it to the provided
   * ImageView. The binding is immediate if the image is found in the cache and
   * will be done asynchronously otherwise. A null bitmap will be associated to
   * the ImageView if an error occurs.
   * 
   * @param url
   *          The URL of the image to download.
   * @param imageView
   *          The ImageView to bind the downloaded image to.
   */
  public void download(String url, ImageView imageView, Photo photo)
  {
    resetPurgeTimer();
    Bitmap bitmap = getBitmapFromCache(url);

    if (bitmap == null)
    {
      try
      {
        forceDownload(url, imageView, photo);
      }
      catch (Exception e)
      {
        Log.w(LOG_TAG, "Error while sending download task for " + url, e);
      }
    }
    else
    {
      cancelPotentialDownload(url, imageView);
      imageView.setImageBitmap(bitmap);
    }
  }

  /*
   * Same as download but the image is always downloaded and the cache is not
   * used. Kept private at the moment as its interest is not clear. private void
   * forceDownload(String url, ImageView view) { forceDownload(url, view, null);
   * }
   */

  /**
   * Same as download but the image is always downloaded and the cache is not
   * used. Kept private at the moment as its interest is not clear.
   * @param photo 
   */
  private void forceDownload(String url, ImageView imageView, Photo photo)
  {
    // State sanity: url is guaranteed to never be null in DownloadedDrawable
    // and cache keys.
    if (url == null)
    {
      imageView.setImageDrawable(null);
      return;
    }
    Mode currentMode = photo == null ? Mode.CORRECT : Mode.NO_DOWNLOADED_DRAWABLE;
    if (currentMode == Mode.NO_DOWNLOADED_DRAWABLE || cancelPotentialDownload(url, imageView))
    {
      switch (currentMode)
      {
        case NO_ASYNC_TASK:
          Bitmap bitmap = downloadBitmap(url);
          addBitmapToCache(url, bitmap);
          imageView.setImageBitmap(bitmap);
          break;

        case NO_DOWNLOADED_DRAWABLE:
          imageView.setMinimumHeight(156);
          BitmapDownloaderTask task = new BitmapDownloaderTask(imageView, photo);
          task.executeOnExecutor(m_hiResExecutor, url);
          break;

        case CORRECT:
          task = new BitmapDownloaderTask(imageView, null);
          DownloadedDrawable downloadedDrawable = new DownloadedDrawable(task);
          imageView.setImageDrawable(downloadedDrawable);
          imageView.setMinimumHeight(156);
          task.executeOnExecutor(m_executor, url);
          break;
      }
    }
  }

  /**
   * Returns true if the current download has been canceled or if there was no
   * download in progress on this image view. Returns false if the download in
   * progress deals with the same url. The download is not stopped in that case.
   */
  private static boolean cancelPotentialDownload(String url, ImageView imageView)
  {
    BitmapDownloaderTask bitmapDownloaderTask = getBitmapDownloaderTask(imageView);

    if (bitmapDownloaderTask != null)
    {
      String bitmapUrl = bitmapDownloaderTask.m_url;
      if ((bitmapUrl == null) || (!bitmapUrl.equals(url)))
      {
        bitmapDownloaderTask.cancel(true);
      }
      else
      {
        // The same URL is already being downloaded.
        return false;
      }
    }
    return true;
  }

  /**
   * @param imageView
   *          Any imageView
   * @return Retrieve the currently active download task (if any) associated
   *         with this imageView. null if there is no such task.
   */
  private static BitmapDownloaderTask getBitmapDownloaderTask(ImageView imageView)
  {
    if (imageView != null)
    {
      Drawable drawable = imageView.getDrawable();
      if (drawable instanceof DownloadedDrawable)
      {
        DownloadedDrawable downloadedDrawable = (DownloadedDrawable) drawable;
        return downloadedDrawable.getBitmapDownloaderTask();
      }
    }
    return null;
  }

  Bitmap downloadBitmap(String url)
  {

    // AndroidHttpClient is not allowed to be used from the main thread
    final HttpClient client = (m_mode == Mode.NO_ASYNC_TASK) ? new DefaultHttpClient() : AndroidHttpClient.newInstance("Android");
    final HttpGet getRequest = new HttpGet(url);

    try
    {
      HttpResponse response = client.execute(getRequest);
      final int statusCode = response.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_OK)
      {
        Log.w(LOG_TAG, "Error " + statusCode + " while retrieving bitmap from " + url);
        return null;
      }

      final HttpEntity entity = response.getEntity();
      if (entity != null)
      {
        InputStream inputStream = null;
        try
        {
          inputStream = entity.getContent();
          // return BitmapFactory.decodeStream(inputStream);
          // Bug on slow connections, fixed in future release.
          return BitmapFactory.decodeStream(new FlushedInputStream(inputStream));
        }
        finally
        {
          if (inputStream != null)
          {
            inputStream.close();
          }
          entity.consumeContent();
        }
      }
    }
    catch (IOException e)
    {
      getRequest.abort();
      Log.w(LOG_TAG, "I/O error while retrieving bitmap from " + url, e);
    }
    catch (IllegalStateException e)
    {
      getRequest.abort();
      Log.w(LOG_TAG, "Incorrect URL: " + url);
    }
    catch (Exception e)
    {
      getRequest.abort();
      Log.w(LOG_TAG, "Error while retrieving bitmap from " + url, e);
    }
    finally
    {
      if ((client instanceof AndroidHttpClient))
      {
        ((AndroidHttpClient) client).close();
      }
    }
    return null;
  }

  /*
   * An InputStream that skips the exact number of bytes provided, unless it
   * reaches EOF.
   */
  static class FlushedInputStream extends FilterInputStream
  {
    public FlushedInputStream(InputStream inputStream)
    {
      super(inputStream);
    }

    @Override
    public long skip(long n) throws IOException
    {
      long totalBytesSkipped = 0L;
      while (totalBytesSkipped < n)
      {
        long bytesSkipped = in.skip(n - totalBytesSkipped);
        if (bytesSkipped == 0L)
        {
          int b = read();
          if (b < 0)
          {
            break; // we reached EOF
          }
          else
          {
            bytesSkipped = 1; // we read one byte
          }
        }
        totalBytesSkipped += bytesSkipped;
      }
      return totalBytesSkipped;
    }
  }

  /**
   * The actual AsyncTask that will asynchronously download the image.
   */
  class BitmapDownloaderTask extends AsyncTask<String, Void, Bitmap>
  {
    private String m_url;
    private Photo m_photo;
    private final WeakReference<ImageView> m_imageViewReference;

    public BitmapDownloaderTask(ImageView imageView, Photo photo)
    {
      this.m_imageViewReference = new WeakReference<ImageView>(imageView);
      this.m_photo = photo;
    }

    /**
     * Actual download method.
     */
    @Override
    protected Bitmap doInBackground(String... params)
    {
      m_url = params[0];
      ImageView imageView = m_imageViewReference.get();
      if(m_photo != null)
      {
        try
        {
          Thread.sleep(500);
        }
        catch (InterruptedException e)
        {}
        if(imageView.getTag() != m_photo)
        {
          Log.w(LOG_TAG, "Cancel before download");
          return null;
        }
      }
      return downloadBitmap(m_url);
    }

    /**
     * Once the image is downloaded, associates it to the imageView
     */
    @Override
    protected void onPostExecute(Bitmap bitmap)
    {
      if(bitmap == null)
      {
        Log.w(LOG_TAG, "Bitmap null : " + m_url);
        return; 
      }

      
      if(m_photo == null)
      {
        addBitmapToCache(m_url, bitmap);
      }
      else
      {
        Log.w(LOG_TAG, "Hires mode downloaded : " + m_url);
        if (m_imageViewReference != null)
        {
          ImageView imageView = m_imageViewReference.get();
          if(imageView.getTag() != m_photo)
          {
            Log.w(LOG_TAG, "Hires mode cancelled");
            return;
          }
          else
          {
            Log.w(LOG_TAG, "Hires, replacing");
            if(bitmap != null) imageView.setImageBitmap(bitmap);
            return;
          }
        }
      }
      
      if (isCancelled())
      {
        bitmap = null;
        Log.i(LOG_TAG, "Cancelled " + m_url);
      }
      
      Log.i(LOG_TAG, "Downloaded (" + m_sHardBitmapCache.size() + ") " + m_url);

      if (m_imageViewReference != null)
      {
        ImageView imageView = m_imageViewReference.get();
        BitmapDownloaderTask bitmapDownloaderTask = getBitmapDownloaderTask(imageView);
        // Change bitmap only if this process is still associated with it
        // Or if we don't use any bitmap to task association
        // (NO_DOWNLOADED_DRAWABLE mode)
        if ((this == bitmapDownloaderTask) || (m_mode != Mode.CORRECT))
        {
          imageView.setImageBitmap(bitmap);
        }
      }
    }
  }

  /**
   * A fake Drawable that will be attached to the imageView while the download
   * is in progress.
   * 
   * <p>
   * Contains a reference to the actual download task, so that a download task
   * can be stopped if a new binding is required, and makes sure that only the
   * last started download process can bind its result, independently of the
   * download finish order.
   * </p>
   */
  static class DownloadedDrawable extends ColorDrawable
  {
    private final WeakReference<BitmapDownloaderTask> m_bitmapDownloaderTaskReference;

    public DownloadedDrawable(BitmapDownloaderTask bitmapDownloaderTask)
    {
      super(Color.WHITE);
      m_bitmapDownloaderTaskReference = new WeakReference<BitmapDownloaderTask>(bitmapDownloaderTask);
    }

    public BitmapDownloaderTask getBitmapDownloaderTask()
    {
      return m_bitmapDownloaderTaskReference.get();
    }
  }

  public void setMode(Mode mode)
  {
    this.m_mode = mode;
    clearCache();
  }

  /*
   * Cache-related fields and methods.
   * 
   * We use a hard and a soft cache. A soft reference cache is too aggressively
   * cleared by the Garbage Collector.
   */

  private static final int HARD_CACHE_CAPACITY = 25;

  private static final int DELAY_BEFORE_PURGE = 60 * 1000; // in milliseconds

  // Hard cache, with a fixed maximum capacity and a life duration
  @SuppressWarnings("serial")
  private final HashMap<String, Bitmap> m_sHardBitmapCache = new LinkedHashMap<String, Bitmap>(HARD_CACHE_CAPACITY / 2, 0.75f, true)
  {
    @Override
    protected boolean removeEldestEntry(LinkedHashMap.Entry<String, Bitmap> eldest)
    {
      if (size() > HARD_CACHE_CAPACITY)
      {
        // Entries push-out of hard reference cache are transferred to soft
        // reference cache
        m_sSoftBitmapCache.put(eldest.getKey(), new SoftReference<Bitmap>(eldest.getValue()));
        return true;
      }
      else
        return false;
    }
  };

  // Soft cache for bitmaps kicked out of hard cache
  private final static ConcurrentHashMap<String, SoftReference<Bitmap>> m_sSoftBitmapCache = new ConcurrentHashMap<String, SoftReference<Bitmap>>(HARD_CACHE_CAPACITY / 2);

  private final Handler m_purgeHandler = new Handler();

  private final Runnable m_purger = new Runnable()
  {
    public void run()
    {
      clearCache();
    }
  };

  /**
   * Adds this bitmap to the cache.
   * 
   * @param bitmap
   *          The newly downloaded bitmap.
   */
  private void addBitmapToCache(String url, Bitmap bitmap)
  {
    if (bitmap != null)
    {
      synchronized (m_sHardBitmapCache)
      {
//      // Use of Android local cache for later
//        String filename = url.replaceAll("http:", "").replace(":", "").replace("/", "");
//        try
//        {
//          File file = new File(m_context.getCacheDir(), filename);
//          FileOutputStream fos = new FileOutputStream(new File(m_context.getCacheDir(), filename));
//          bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
//          fos.close();
//        }
//        catch (Exception e)
//        {
//          Log.w(LOG_TAG, "Error while writing bitmap to " + filename, e);
//        }
        m_sHardBitmapCache.put(url, bitmap);
      }
    }
  }

  /**
   * @param url
   *          The URL of the image that will be retrieved from the cache.
   * @return The cached bitmap or null if it was not found.
   */
  private Bitmap getBitmapFromCache(String url)
  {
    // First try the hard reference cache
    synchronized (m_sHardBitmapCache)
    {
      final Bitmap bitmap = m_sHardBitmapCache.get(url);
      if (bitmap != null)
      {
        // Bitmap found in hard cache
        // Move element to first position, so that it is removed last
        m_sHardBitmapCache.remove(url);
        m_sHardBitmapCache.put(url, bitmap);
        return bitmap;
      }
//      // Use of Android local cache for later
//      else
//      {
//        String filename = url.replaceAll("http:", "").replace(":", "").replace("/", "");
//        File cached = new File(m_context.getCacheDir(), filename);
//        if(cached.exists())
//          return BitmapFactory.decodeFile(cached.getPath());
//      }
    }

    // Then try the soft reference cache
    SoftReference<Bitmap> bitmapReference = m_sSoftBitmapCache.get(url);
    if (bitmapReference != null)
    {
      final Bitmap bitmap = bitmapReference.get();
      if (bitmap != null)
      {
        // Bitmap found in soft cache
        return bitmap;
      }
      else
      {
        // Soft reference has been Garbage Collected
        m_sSoftBitmapCache.remove(url);
      }
    }

    return null;
  }

  /**
   * Clears the image cache used internally to improve performance. Note that
   * for memory efficiency reasons, the cache will automatically be cleared
   * after a certain inactivity delay.
   */
  public void clearCache()
  {
    m_sHardBitmapCache.clear();
    m_sSoftBitmapCache.clear();
  }

  /**
   * Allow a new delay before the automatic cache clear is done.
   */
  private void resetPurgeTimer()
  {
    m_purgeHandler.removeCallbacks(m_purger);
    m_purgeHandler.postDelayed(m_purger, DELAY_BEFORE_PURGE);
  }
}
