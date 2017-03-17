
import com.aesean.utils.JsonUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

public class ZhihuSpider {
    private static OkHttpClient sOkHttpClient;
    /**
     * 爬取到的数据保存的文件
     */
    private static File mFile = new File("spider.txt");
    /**
     * 这里用一个消息队列来打印爬取的数据，避免多线程打印导致的数据错乱。
     */
    private static final Queue<Runnable> mPrintQueue = new ArrayDeque<>();

    public static void main(String[] args) {
        try {
            if (!mFile.exists() && !mFile.createNewFile()) {
                System.err.println("文件创建失败");
                return;
            }
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("文件创建异常：" + e.getMessage());
            return;
        }
        startPrintThread();
        startHeartBeatThread();
        int count = Runtime.getRuntime().availableProcessors() - 1;
        sOkHttpClient = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(count, 5, TimeUnit.MINUTES))
                .build();
        topTask();
    }

    /**
     * 启动一个单独线程用来打印爬取到的数据
     */
    private static void startPrintThread() {
        new Thread(() -> {
            //noinspection InfiniteLoopStatement
            while (true) {
                Runnable runnable = null;
                synchronized (mPrintQueue) {
                    if (mPrintQueue.isEmpty()) {
                        try {
                            mPrintQueue.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    } else {
                        runnable = mPrintQueue.poll();
                    }
                }
                if (runnable != null) {
                    runnable.run();
                }
            }
        }).start();
    }

    /**
     * 启动一个心跳线程，如果10秒没有收到打印任务就退出
     */
    private static void startHeartBeatThread() {
        new Thread(new Runnable() {
            boolean needExit = false;

            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    synchronized (mPrintQueue) {
                        if (mPrintQueue.isEmpty()) {
                            if (needExit) {
                                System.out.println("超过10秒没发现新任务，退出程序");
                                System.exit(0);
                            }
                            needExit = true;
                        } else {
                            needExit = false;
                        }
                    }
                }
            }
        }).start();
    }

    private static void topTask() {
        for (int i = 0; i < 1000; i += 50) {
            topTask(String.valueOf(i), "50");
        }
    }

    private static void answerTask(String questionsId) {
        String url = "https://api.zhihu.com/questions/" + questionsId + "/answers?order_by";
        sOkHttpClient.newCall(createZhihuHeader().get().url(url).build())
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {

                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        String result = response.body().string();
                        JSONObject jsonObject = JSONObject.parseObject(result);
                        JSONArray dataArray = jsonObject.getJSONArray("data");
                        if (dataArray != null) {
                            for (int i = 0, size = dataArray.size(); i < size; i++) {
                                // 这里根据自己需要自己解析需要爬取的内容
                                JSONObject data = dataArray.getJSONObject(i);
                                String title = JsonUtil.getString(data, "question.title");
                                String voteup_count = JsonUtil.getString(data, "voteup_count");
                                String url = JsonUtil.getString(data, "url");
                                String author = JsonUtil.getString(data, "author.name");
                                String content = JsonUtil.getString(data, "excerpt");
                                createPrintTask(title + "\n"
                                        + "voteup_count = " + voteup_count + " author = " + author + "\n"
                                        + url + "\n"
                                        + content + "\n\n\n");
                            }
                        }
                    }
                });
    }

    private static void createPrintTask(String s) {
        // 创建打印任务
        Runnable printRunnable = printRunnable(s);
        // 通知打印线程打印数据
        synchronized (mPrintQueue) {
            mPrintQueue.add(printRunnable);
            mPrintQueue.notifyAll();
        }
    }

    private static Runnable printRunnable(String s) {
        return () -> {
            try {
                Files.write(Paths.get(mFile.getAbsolutePath()), s.getBytes(), StandardOpenOption.APPEND);
            } catch (Exception e) {
                e.printStackTrace();
            }
        };
    }

    private static void topTask(String beforeId, String limit) {
        String url = "https://api.zhihu.com/topstory"
                + "?action=pull"
                + "&session_token=08328eb1bdf12e34df7565ef"
                + "&resolution=1920x1080"
                + "&before_id=" + beforeId
                + "&limit=" + limit;
        sOkHttpClient.newCall(createZhihuHeader()
                .get()
                .url(url)
                .build())
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        String result = response.body().string();
                        JSONObject jsonObject = JSONObject.parseObject(result);
                        JSONArray dataArray = jsonObject.getJSONArray("data");
                        for (int i = 0, size = dataArray.size(); i < size; i++) {
                            JSONObject data = dataArray.getJSONObject(i);
                            String questionId = JsonUtil.getString(data, "target.question.id");
                            answerTask(questionId);
                        }
                    }
                });
    }

    private static Request.Builder createZhihuHeader() {
        // 这里header可以实际用一台真实设备抓https包，拿到这里的header，下面的数据是用一台小米4c实际抓包拿到的head
        Request.Builder builder = new Request.Builder();
        return builder.header("User-Agent", "Futureve/4.16.0 Mozilla/5.0 (Linux; Android 5.1.1; Mi-4c Build/LMY47V; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/56.0.2924.87 Mobile Safari/537.36 Google-HTTP-Java-Client/1.22.0 (gzip)")
                .header("x-udid", "AGCCq92ZdgtLBeuD8pPGSwHd-1S4j1Fsn_Y=")
                .header("x-app-local-unique-id", "1052702")
                .header("x-app-version", "4.16.0")
                .header("x-api-version", "3.0.51")
                .header("Authorization", "Bearer gt1.0AAAAAARnfoILdpndq4JgAAAAAAxNVQJgAo-8Qh-vGnXNGb5ll9WFt2byTe8D")
                .header("Cookie", "aliyungf_tc=AQAAAIYhvyXDXAwAAh+ntMbmGXQ9nwfi");
    }

}
