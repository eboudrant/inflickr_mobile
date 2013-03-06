//
//  InflikrAdapter
//
//  Copyright 2013 infli.kr mobile
//  https://github.com/eboudrant/inflickr_mobile
//

package kr.infli;

import java.text.SimpleDateFormat;
import java.util.Locale;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Typeface;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.googlecode.flickrjandroid.photos.Photo;
import com.googlecode.flickrjandroid.photos.PhotoList;

public class InflikrAdapter extends BaseAdapter implements AbsListView.OnScrollListener
{
  static final String LOG_TAG = "InflikrAdapter";
  
  private static final SimpleDateFormat FORMATTER = new SimpleDateFormat("MMM, dd, yyyy", Locale.US);

  private Point m_displaySize;

  private ListView m_listView;

  public static class ViewsHolder
  {
    RelativeLayout m_container;

    RelativeLayout m_header;

    RelativeLayout m_footer;

    TextView m_text;

    ImageView m_avatar;

    ImageView m_photo;

    TextView m_date;
  }

  private final InflikrDownloader m_imageDownloader;

  private Context m_context;

  private PhotoList m_photoList;
  
  private Typeface m_font;

  public InflikrAdapter(Context context, PhotoList photoList)
  {
    this.m_photoList = photoList;
    this.m_context = context;
    
    WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    Display display = wm.getDefaultDisplay();
    m_displaySize = new Point();
    display.getSize(m_displaySize);
    
    m_font = Typeface.createFromAsset(context.getAssets(), "fonts/Slackey.ttf");
    
    m_imageDownloader = new InflikrDownloader(context);
    
  }

  public int getCount()
  {
    return m_photoList == null ? 0 : m_photoList.size();
  }

  public Photo getItem(int position)
  {
    return m_photoList.get(position);
  }

  public long getItemId(int position)
  {
    return getItem(position).hashCode();
  }

  /**
   * Load/Recycle the view, all image are loaded mode async mode.
   */
  public View getView(int position, View convertView, ViewGroup listView)
  {

    ViewsHolder holder = null;
    if (convertView == null)
    {
      if (m_listView == null)
      {
        // Attach the listener
        m_listView = (ListView) listView;
        m_listView.setOnScrollListener(this);
      }
      // Create a new view
      LayoutInflater inflater = (LayoutInflater) m_context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
      convertView = inflater.inflate(R.layout.picture, listView, false);
      // Keep all views in the holder so we minimize the number of findViewById invocations
      holder = new ViewsHolder();
      holder.m_footer = (RelativeLayout) convertView.findViewById(R.id.footer);
      holder.m_header = (RelativeLayout) convertView.findViewById(R.id.header);
      holder.m_header.setBackgroundColor(Color.WHITE);
      holder.m_header.setAlpha(0.8f);
      holder.m_photo = (ImageView) convertView.findViewById(R.id.photo);
      holder.m_text = (TextView) convertView.findViewById(R.id.title);
      holder.m_text.setTextColor(Color.rgb(0, 99, 220));
      holder.m_text.setTypeface(m_font);
      holder.m_avatar = (ImageView) convertView.findViewById(R.id.avatar);
      holder.m_date = (TextView) convertView.findViewById(R.id.date);
      holder.m_date.setTextColor(Color.rgb(255, 0, 132));
      holder.m_date.setTypeface(m_font);
      // Use the screen width with a 24x36 ratio
      holder.m_photo.setLayoutParams(new LinearLayout.LayoutParams(m_displaySize.x, (int) ((float) m_displaySize.x * 0.68f)));
      holder.m_container = (RelativeLayout) convertView;
      convertView.setTag(holder);
      // So the header is over the photo 
      holder.m_header.bringToFront();
    }
    else
    {
      // Recycle existing view
      holder = (ViewsHolder) convertView.getTag();
    }
    
    // Get the photo fill labels and reauest image download
    Photo photo = m_photoList.get(position);
    m_imageDownloader.download(photo.getOwner().getBuddyIconUrl(), holder.m_avatar, null);
    m_imageDownloader.download(photo.getSmallUrl(), holder.m_photo, null);
    holder.m_photo.setTag(photo);
    holder.m_text.setText(" " + (photo.getTitle().equals("") ? "Untitled" : photo.getTitle()) + " by " + photo.getOwner().getUsername());
    if(photo.getDateTaken() != null)
    {
      holder.m_date.setText("Taken on " + FORMATTER.format(photo.getDateTaken()));
    }
    else if(photo.getDatePosted() != null)
    {
      holder.m_date.setText("Uploaded on " + FORMATTER.format(photo.getDatePosted()));
    }
    else
    {
      holder.m_date.setText("No date");
    }
    
    // By default always reset the header to the top
    holder.m_header.setY(0);
    
    return convertView;
  }

  /**
   * On scroll we adjust the header y coord so 
   * - it is always on the item
   * - two headers do not overlap
   */
  @Override
  public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount)
  {
    View firstChild = view.getChildAt(0);
    if (firstChild != null)
    {
      View secondChild = view.getChildAt(1);
      ViewsHolder first = (ViewsHolder) firstChild.getTag();
      int cap = 0;
      if (secondChild != null)
      {
        // Push the previous header if the second is here
        ViewsHolder second = (ViewsHolder) secondChild.getTag();
        cap = Math.min(0, (int) secondChild.getY() - first.m_header.getHeight());
        // And always reset to 0 the second header, onScroll do not fire for all pixel move.
        second.m_header.setY(0);
      }
      // Recompute the Y of the first header
      float y = Math.max(0, -1 * firstChild.getY() + cap);
      first.m_header.setY(y);
    }

  }

  /**
   * When the scroll end we send download request for medium resolution
   */
  @Override
  public void onScrollStateChanged(AbsListView view, int scrollState)
  {
    if(scrollState == OnScrollListener.SCROLL_STATE_IDLE)
    {
      // Scroll ended, let's download an hi-res
      for (int i = 0; i < view.getChildCount(); i++)
      {
        View child = view.getChildAt(i);
        Photo photo = m_photoList.get(view.getPositionForView(child));
        ViewsHolder holder = (ViewsHolder) child.getTag();
        m_imageDownloader.download(photo.getMediumUrl(), holder.m_photo, photo);
      }
    }
  }
}
