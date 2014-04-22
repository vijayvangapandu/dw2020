package com.eharmony.example;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.View;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.eharmony.example.disruptor.factory.FiveHundredPxPhotoContainerFactory;
import com.eharmony.example.disruptor.handler.FiveHundredPxPhotoContainerHandler;
import com.eharmony.example.disruptor.translator.FiveHundredPxPhotoContainerProducerWithTranslator;
import com.eharmony.example.exception.AuthenticationError;
import com.eharmony.example.service.FiveHundredPxGsonSpiceService;
import com.eharmony.example.service.FiveHundredPxJacksonSpiceService;
import com.eharmony.example.tasks.FiveHundredPxAccessToken;
import com.fivehundredpx.api.auth.AccessToken;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.nhaarman.listviewanimations.swinginadapters.prepared.SwingBottomInAnimationAdapter;
import com.octo.android.robospice.SpiceManager;
import com.octo.android.robospice.persistence.DurationInMillis;
import com.octo.android.robospice.request.listener.RequestListener;
import com.octo.android.robospice.persistence.exception.SpiceException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.eharmony.example.model.FiveHundredPxConfiguration;
import com.eharmony.example.widgets.adapter.PhotoAdapter;
import com.eharmony.example.widgets.listener.InfiniteScrollListener;

import butterknife.ButterKnife;
import butterknife.InjectView;
import rx.Observable;
import rx.Subscriber;
import rx.android.observables.AndroidObservable;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;
import rx.schedulers.Schedulers;

import com.eharmony.example.model.FiveHundredPxPhotoContainer;
import com.eharmony.example.service.FiveHundredPxSpiceRequest;
import com.eharmony.example.service.client.FiveHundredPxClient;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends BaseSpiceActivity {

    private final Logger LOGGER = LoggerFactory.getLogger(MainActivity.class);

    private PhotoAdapter listAdapter;

    @InjectView(R.id.list)
    ListView listView;
    @InjectView(R.id.progress)
    ProgressBar progressBar;
    @InjectView(R.id.debug_text)
    TextView debugTextView;

    private Handler handler;
    private Handler uiHandler;

    private FiveHundredPxPhotoContainerProducerWithTranslator producer;

    private State state = State.INITIAL;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.inject(this);
        this.uiHandler = new Handler();
        startObservables();
    }

    private Observable<BaseAdapter> getTokenObservable() {
        return AndroidObservable.bindActivity(this, Observable.create(new Observable.OnSubscribe<BaseAdapter>() {
            @Override
            public void call(Subscriber<? super BaseAdapter> subscriber) {
                try {
                    setupNetworkServices();
                    final BaseAdapter baseAdapter = getNewListViewAdapter();
                    setupDisruptors();
                    subscriber.onNext(baseAdapter);
                    subscriber.onCompleted();
                } catch (Exception e) {
                    LOGGER.error(e.getMessage());
                    subscriber.onError(e);
                }
            }
        }));
    }

    private Action1<BaseAdapter> getInitialLoadMoreDataAction() {
        return new Action1<BaseAdapter>() {
            @Override
            public void call(final BaseAdapter adapter) {
                addAdapterToListView(adapter);
                MainActivity.this.handler.post(new Runnable() {
                    @Override
                    public void run() {
                        loadMoreData(MainActivity.this.state);
                    }
                });
                loadMoreData(addAdapterToListView(adapter));
            }
        };
    }

    private Action1<Throwable> getRetryDialogAction(final Runnable successRunnable, final Runnable failureRunnable) {
        return new Action1<Throwable>() {
            @Override
            public void call(Throwable throwable) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.oops)
                        .setMessage(throwable.getMessage())
                        .setPositiveButton(getString(R.string.retry), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                successRunnable.run();
                            }
                        })
                        .setNegativeButton(getString(R.string.cancel), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                failureRunnable.run();
                            }
                        }).create().show();
            }
        };
    }

    @Override
    public void startObservables() {
        getTokenObservable()
                .retry(getResources().getInteger(R.integer.max_retries))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread()).subscribe(
                getInitialLoadMoreDataAction(),
                getRetryDialogAction(
                        new Runnable() {
                            @Override
                            public void run() {
                                startObservables();
                            }
                        }, new Runnable() {
                            @Override
                            public void run() {
                                finish();
                            }
                        }
                )
        );
    }

    private void setupDisruptors() {
        Executor executor = Executors.newSingleThreadExecutor();
        FiveHundredPxPhotoContainerFactory factory = new FiveHundredPxPhotoContainerFactory();
        int bufferSize = 4;
        Disruptor<FiveHundredPxPhotoContainer> disruptor = new Disruptor<FiveHundredPxPhotoContainer>(factory, bufferSize, executor, ProducerType.SINGLE, new BlockingWaitStrategy());
        disruptor.handleEventsWith(new FiveHundredPxPhotoContainerHandler(this.listAdapter, MainActivity.this.uiHandler));
        disruptor.start();
        this.producer = new FiveHundredPxPhotoContainerProducerWithTranslator(disruptor.getRingBuffer());
        final HandlerThread handlerThread = new HandlerThread("photo_retrieval_thread");
        handlerThread.start();
        this.handler = new Handler(handlerThread.getLooper());
    }

    private void setupNetworkServices() throws AuthenticationError {
        final AccessToken accessToken = FiveHundredPxAccessToken.getAccessToken(FiveHundredPxConfiguration.INSTANCE);
        FiveHundredPxClient.INSTANCE.setConsumer(accessToken);

        final SpiceManager spiceManager = new SpiceManager(FiveHundredPxJacksonSpiceService.class);
        addToSpiceManager(MainActivity.class.getName(), spiceManager);
        spiceManager.start(this);
    }

    private void addListViewListeners(final int scrollerItemThresholdForPreload) {
        this.listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {

            }
        });

        this.listView.setOnScrollListener(new InfiniteScrollListener(scrollerItemThresholdForPreload) {
            @Override
            public void onLoadMore(int page, int totalItemsCount) {
                loadMoreData(State.NEXT);
            }
        });

    }

    private BaseAdapter getNewListViewAdapter() throws AuthenticationError {
        final int resultsPerPage = getResources().getInteger(R.integer.results_per_page);

        addListViewListeners(resultsPerPage / 2);

        this.listAdapter = new PhotoAdapter(this);
        this.listAdapter.setResultsPerPage(resultsPerPage);

        final SwingBottomInAnimationAdapter swingBottomInAnimationAdapter = new SwingBottomInAnimationAdapter(this.listAdapter);
        swingBottomInAnimationAdapter.setInitialDelayMillis(getResources().getInteger(R.integer.transition_delay_duration_in_millis));
        swingBottomInAnimationAdapter.setAbsListView(this.listView);

        return swingBottomInAnimationAdapter;
    }

    private State addAdapterToListView(final BaseAdapter adapter) {
        this.listView.setAdapter(adapter);
        return State.INITIAL;
    }

    public void loadMoreData(final State loadState) {
        if (State.INITIAL == loadState || this.listAdapter.hasMorePages()) {
            final FiveHundredPxSpiceRequest request = new FiveHundredPxSpiceRequest(this.listAdapter.getCurrentPage(), this.listAdapter.getResultsPerPage());
            getSpiceManager(MainActivity.class.getName()).execute(request, request, DurationInMillis.ONE_MINUTE, new FiveHundredPxSpiceRequestListener(loadState));
        }
    }

    private class FiveHundredPxSpiceRequestListener implements RequestListener<FiveHundredPxPhotoContainer> {
        final State loadState;

        public FiveHundredPxSpiceRequestListener(final State loadState) {
            this.loadState = loadState;
        }

        @Override
        public void onRequestFailure(final SpiceException spiceException) {
            LOGGER.error(spiceException.getMessage());
            Toast.makeText(MainActivity.this, getString(R.string.connection_failed), Toast.LENGTH_LONG).show();
            loadMoreData(loadState);
        }

        @Override
        public void onRequestSuccess(final FiveHundredPxPhotoContainer result) {
            if (State.INITIAL == this.loadState) {
                MainActivity.this.progressBar.setVisibility(View.GONE);
                MainActivity.this.debugTextView.setVisibility(View.VISIBLE);
                MainActivity.this.state = State.NEXT;
            }

            MainActivity.this.producer.onData(result);
        }
    }

    private enum State {
        INITIAL, NEXT
    }
}
