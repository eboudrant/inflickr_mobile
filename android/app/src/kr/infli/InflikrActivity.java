//
//  InflikrActivity.java
//
//  Copyright 2013 infli.kr mobile
//  https://github.com/eboudrant/inflickr_mobile
//

import java.util.HashSet;
import java.util.Set;

import android.app.Activity;
import android.app.ProgressDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.ListView;
import android.widget.TextView;

import com.googlecode.flickrjandroid.Flickr;
import com.googlecode.flickrjandroid.photos.Extras;
import com.googlecode.flickrjandroid.photos.PhotoList;
import com.googlecode.flickrjandroid.photos.SearchParameters;

public class InflikrActivity extends Activity
{
  
  @Override
  protected void onCreate(Bundle savedInstanceState)
  {
    this.requestWindowFeature(Window.FEATURE_NO_TITLE);
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    new FlickrLoadTask().execute();
  }
  
  private class FlickrLoadTask extends AsyncTask<String, Void, PhotoList>
  {
    private ProgressDialog m_dialog;

    @Override
    protected void onPreExecute()
    {
      m_dialog = new ProgressDialog(InflikrActivity.this){
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            View view = this.findViewById(android.R.id.message);
            if (view != null && view instanceof TextView) {
                TextView tv = (TextView) view;
                Typeface font = Typeface.createFromAsset(InflikrActivity.this.getAssets(), "fonts/Slackey.ttf");
                tv.setTypeface(font);
                tv.setTextColor(Color.rgb(0, 99, 220));
            }
        }
      };
      m_dialog.setMessage("Loading photos...");
      m_dialog.setCancelable(false);
      m_dialog.setCanceledOnTouchOutside(false);
      m_dialog.show();
    }

    @Override
    protected PhotoList doInBackground(String... params)
    {
      try
      {
        Set<String> extras = new HashSet<String>();
        extras.add(Extras.DATE_TAKEN);
        extras.add(Extras.DATE_UPLOAD);
        extras.add(Extras.OWNER_NAME);

        String apiKey = "API_KEY";
        Flickr f = new Flickr(apiKey);

        SearchParameters searchParameters = new SearchParameters();
        searchParameters.setTags(new String[] { "hongkong", "tokyo", "portra", "velvia", "ektar", "trix" });
        searchParameters.setSort(SearchParameters.INTERESTINGNESS_DESC);
        searchParameters.setExtras(extras);
        // 500 is the max on flickr API
        return f.getPhotosInterface().search(searchParameters, 500, 0);
      }
      catch (Exception e)
      {
        e.printStackTrace();
        return null;
      }
      finally
      {
        m_dialog.dismiss();
      }
    }

    @Override
    protected void onPostExecute(PhotoList photoList)
    {
      ListView photos = (ListView) InflikrActivity.this.findViewById(R.id.listView1);
      photos.setAdapter(new InflikrAdapter(InflikrActivity.this, photoList));
      InflikrActivity.this.startSearch("", true, new Bundle(), false);
    }
  }

}
