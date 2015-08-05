/*
 * WikiFragment.java
 * Copyright (C) 2015 Nicholas Killewald
 *
 * This file is distributed under the terms of the BSD license.
 * The source package should have a LICENSE file at the toplevel.
 */

package net.exclaimindustries.geohashdroid.fragments;

import android.app.Fragment;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import net.exclaimindustries.geohashdroid.R;
import net.exclaimindustries.geohashdroid.services.WikiService;
import net.exclaimindustries.geohashdroid.util.GHDConstants;
import net.exclaimindustries.geohashdroid.util.Info;
import net.exclaimindustries.geohashdroid.util.UnitConverter;
import net.exclaimindustries.tools.BitmapTools;
import net.exclaimindustries.tools.LocationUtil;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import java.io.IOException;

/**
 * <code>WikiFragment</code> does double duty, handling what both of <code>WikiPictureEditor</code>
 * and <code>WikiMessageEditor</code> used to do.  Well, most of it.  Honestly,
 * most of that's been dumped into {@link WikiService}, but the interface part
 * here can handle either pictures or messages.
 */
public class WikiFragment extends CentralMapExtraFragment
        implements GoogleApiClient.ConnectionCallbacks,
                   GoogleApiClient.OnConnectionFailedListener {
    /** Boolean argument indicating this needs to track location itself. */
    public static final String TRACK_LOCATION = "trackLocation";
    /** Bunde key for the Info object. */
    public static final String INFO = "info";

    private static final int GET_PICTURE = 1;

    private View mAnonWarning;
    private View mPictureCaptionNote;
    private ImageButton mGalleryButton;
    private CheckBox mPictureCheckbox;
    private CheckBox mStampInfoboxCheckbox;
    private View mLocationLayout;
    private TextView mLocationView;
    private TextView mDistanceView;
    private EditText mMessage;
    private Button mPostButton;

    private Info mInfo;
    private GoogleApiClient mGClient;
    private Location mLastLocation;

    private Uri mPictureUri;

    private LocationListener mLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            // DING.
            mLastLocation = location;
            updateLocation();
        }
    };

    private SharedPreferences.OnSharedPreferenceChangeListener mPrefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            // Huh, we register for ALL changes, not just for a few prefs.  May
            // as well narrow it down...
            if(key.equals(GHDConstants.PREF_WIKI_USER) || key.equals(GHDConstants.PREF_WIKI_PASS)) {
                checkAnonStatus();
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // First, see if there's an instance state.
        if(savedInstanceState != null) {
            // If so, use the info in there.  Assuming it exists.
            mInfo = savedInstanceState.getParcelable(INFO);
        }

        // Was it null?  Perhaps it was...
        if(mInfo == null) {
            Bundle args = getArguments();
            if(args != null) {
                mInfo = args.getParcelable(INFO);
            }
        }

        // And hey, look, it's a GoogleApiClient again.  Surprise, surprise.
        mGClient = new GoogleApiClient.Builder(getActivity())
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View layout = inflater.inflate(R.layout.wiki, container, false);

        // Views!
        mAnonWarning = layout.findViewById(R.id.wiki_anon_warning);
        mPictureCaptionNote = layout.findViewById(R.id.wiki_message_is_caption);
        mLocationLayout = layout.findViewById(R.id.wiki_location_block);
        mPictureCheckbox = (CheckBox)layout.findViewById(R.id.wiki_check_include_picture);
        mStampInfoboxCheckbox = (CheckBox)layout.findViewById(R.id.wiki_check_infobox);
        mGalleryButton = (ImageButton)layout.findViewById(R.id.wiki_thumbnail);
        mPostButton = (Button)layout.findViewById(R.id.wiki_post_button);
        mMessage = (EditText)layout.findViewById(R.id.wiki_message);
        mLocationView = (TextView)layout.findViewById(R.id.wiki_current_location);
        mDistanceView = (TextView)layout.findViewById(R.id.wiki_distance);

        // The picture checkbox determines if the other boxes are visible or
        // not.
        mPictureCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                resolvePictureControlVisibility();
            }
        });

        // The gallery button needs to fire off to the gallery.  Or Photos.  Or
        // whatever's listening for this intent.
        mGalleryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(
                        new Intent(
                                Intent.ACTION_PICK,
                                android.provider.MediaStore.Images.Media.INTERNAL_CONTENT_URI),
                        GET_PICTURE);
            }
        });

        return layout;
    }

    @Override
    public void onStart() {
        super.onStart();

        // BOOM!  Connect!
        mGClient.connect();
    }

    @Override
    public void onStop() {
        // Stop!
        if(mGClient != null) {
            LocationServices.FusedLocationApi.removeLocationUpdates(mGClient, mLocationListener);
            mGClient.disconnect();
        }

        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();

        // We do the anon checks on resume, since it's possible that the user
        // came back from preferences and the anon states have changed.
        checkAnonStatus();

        // Plus, resubscribe for those changes.
        PreferenceManager.getDefaultSharedPreferences(getActivity()).registerOnSharedPreferenceChangeListener(mPrefListener);
    }

    @Override
    public void onPause() {
        // Stop listening for changes.  We'll redo anon checks on resume anyway.
        PreferenceManager.getDefaultSharedPreferences(getActivity()).unregisterOnSharedPreferenceChangeListener(mPrefListener);

        super.onPause();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch(requestCode) {
            case GET_PICTURE: {
                if(data != null) {
                    // Picture in!  We need to stash the URL away and make a
                    // thumbnail out of it, if we can!
                    Uri uri = data.getData();

                    if(uri == null)
                        return;

                    // Grab a new Bitmap.  We'll toss this into the button.
                    int dimen = getResources().getDimensionPixelSize(R.dimen.wiki_nominal_icon_size);
                    Bitmap thumbnail = BitmapTools
                            .createRatioPreservedDownscaledBitmapFromUri(
                                    getActivity(),
                                    uri,
                                    dimen,
                                    dimen,
                                    true
                            );

                    // Good!  Was it null?
                    if(thumbnail == null) {
                        // NO!  WRONG!  BAD!
                        Toast.makeText(getActivity(), R.string.wiki_generic_image_error, Toast.LENGTH_LONG).show();
                        return;
                    }

                    // With bitmap in hand...
                    mGalleryButton.setImageBitmap(thumbnail);

                    // And remember it for posting later.  Done!
                    mPictureUri = uri;
                }
            }
            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /**
     * Sets the Info.  Whatever gets set here will override any arguments
     * originally passed in if and when onSaveInstanceState is needed.  Please
     * don't make this null.
     *
     * @param info the new Info
     */
    public void setInfo(Info info) {
        mInfo = info;

        // TODO: Update the display.
    }

    /**
     * Sets whether or not the current location section is visible.  It should
     * be visible if we're on a phone.  On a tablet, CentralMap is still there,
     * meaning the InfoBox that's usually there will suffice.  In either case,
     * the most recent location from the location service will be what's
     * actually sent to the wiki.  This is mainly just for the interface.
     *
     * @param enabled true to track, false to not
     */
    public void setTrackLocation(boolean enabled) {
        if(enabled) {
            // On go the views!
            mLocationView.setVisibility(View.VISIBLE);
            mDistanceView.setVisibility(View.VISIBLE);
        } else {
            // Out go the views!
            mLocationView.setVisibility(View.GONE);
            mDistanceView.setVisibility(View.GONE);
        }
    }

    private void checkAnonStatus() {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // A user is anonymous if they either have no username or no
                // password (the wiki doesn't allow passwordless users, which
                // would just be silly anyway).
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());

                String username = prefs.getString(GHDConstants.PREF_WIKI_USER, "");
                String password = prefs.getString(GHDConstants.PREF_WIKI_PASS, "");

                if(username.isEmpty() || password.isEmpty()) {
                    // If anything isn't defined, we can't set a picture.  Also,
                    // uncheck the picture checkbox just to make sure.
                    mPictureCheckbox.setChecked(false);
                    mPictureCheckbox.setVisibility(View.GONE);
                    mGalleryButton.setVisibility(View.GONE);
                    mPictureCaptionNote.setVisibility(View.GONE);
                    mStampInfoboxCheckbox.setVisibility(View.GONE);
                    mAnonWarning.setVisibility(View.VISIBLE);
                } else {
                    // Now, we can't just turn everything back on without
                    // checking.  But we CAN get rid of the anon warning and
                    // bring back the picture checkbox.
                    mAnonWarning.setVisibility(View.GONE);
                    mPictureCheckbox.setVisibility(View.VISIBLE);
                }

                // Now, make sure everything else is up to date, including the
                // text on the post button.  This will do some redundant checks
                // in the case of hiding things, but meh.
                resolvePictureControlVisibility();
            }
        });
    }

    private void resolvePictureControlVisibility() {
        // One checkbox to rule them all!
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(mPictureCheckbox.isChecked()) {
                    mGalleryButton.setVisibility(View.VISIBLE);
                    mPictureCaptionNote.setVisibility(View.VISIBLE);
                    mStampInfoboxCheckbox.setVisibility(View.VISIBLE);

                    // Oh, and update the button string, too.
                    mPostButton.setText(R.string.wiki_dialog_submit_picture);
                } else {
                    mGalleryButton.setVisibility(View.GONE);
                    mPictureCaptionNote.setVisibility(View.GONE);
                    mStampInfoboxCheckbox.setVisibility(View.GONE);
                    mPostButton.setText(R.string.wiki_dialog_submit_message);
                }
            }
        });
    }

    private void resolvePostButtonEnabledness() {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean hasMessage = !(mMessage.getText().toString().isEmpty());

                if(!mPictureCheckbox.isChecked()) {
                    // If we're in message mode, then we just need to make sure
                    // there's a message.
                    mPostButton.setEnabled(hasMessage);
                } else {
                    // Otherwise, we also need to make sure there's a picture to
                    // go with it.

                }
            }
        });
    }

    private void updateLocation() {
        // If we're not ready yet, don't bother.
        if(mLocationView == null || mDistanceView == null || mInfo == null) return;

        // Easy enough, this is just the current location and the distance.
        if(mLastLocation == null) {
            // Or not, if there's no location.
            mLocationView.setText(R.string.standby_title);
            mDistanceView.setText(R.string.standby_title);
        } else {
            mLocationView.setText(UnitConverter.makeFullCoordinateString(getActivity(), mLastLocation, false, UnitConverter.OUTPUT_SHORT));
            mLocationView.setText(UnitConverter.makeDistanceString(getActivity(), UnitConverter.DISTANCE_FORMAT_SHORT, mLastLocation.distanceTo(mInfo.getFinalLocation())));
        }
    }

    @Override
    public void onConnected(Bundle bundle) {
        // Hi, API client!
        Location loc = LocationServices.FusedLocationApi.getLastLocation(mGClient);

        if(LocationUtil.isLocationNewEnough(loc))
            mLastLocation = loc;
        else
            mLastLocation = null;

        updateLocation();

        // Now, we'll listen for updates no matter what.  This way we're always
        // assured a fresh location.
        LocationRequest lRequest = LocationRequest.create();
        lRequest.setInterval(1000);
        lRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        LocationServices.FusedLocationApi.requestLocationUpdates(mGClient, lRequest, mLocationListener);
    }

    @Override
    public void onConnectionSuspended(int i) {
        // OH CRAP
        mLastLocation = null;
        updateLocation();
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        // OH CRAP OH CRAP OH CRAP OH CRAP OH CRAP
    }

    @NonNull
    @Override
    public FragmentType getType() {
        return FragmentType.WIKI;
    }
}
