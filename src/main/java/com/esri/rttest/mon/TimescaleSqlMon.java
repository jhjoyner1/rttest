package com.esri.rttest.mon;

import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;

public class TimescaleSqlMon {
  private static final Logger LOG = LogManager.getLogger(SolrIndexMon.class);

  class CheckCount extends TimerTask {

    long cnt1;
    long cnt2;
    long startCount;
    long endCount;
    int numSamples;
    HashMap<Long, Long> samples;
    long t1;
    long t2;
    SimpleRegression regression;

    public CheckCount() {
      regression = new SimpleRegression();
      cnt1 = 0;
      cnt2 = -1;
      startCount = 0;
      numSamples = 0;
      t1 = 0L;
      t2 = 0L;
      samples = null;
    }

    boolean inCounting() {
      if (cnt1 > 0) {
        return true;
      } else {
        return false;
      }
    }

    HashMap<Long, Long> getSamples() {
      return samples;
    }

    long getStartCount() {
      return startCount;
    }

    long getEndCount() {
      return endCount;
    }

    @Override
    public void run() {
      try {

        LOG.info("Checking Count");

        //String url = "jdbc:postgresql://$kTimescaleHost:5432/$schema";
        Properties properties = new Properties();
        properties.put("user", "realtime");
        properties.put("password", "esri.test");

        Connection connection = DriverManager.getConnection(connectionUrl, properties);

        Statement statement = connection.createStatement();
        if(hyperTablePrefix == null || "".equalsIgnoreCase(hyperTablePrefix)) {
          //identify sub-tables of hypertable
          ResultSet chunks = statement.executeQuery("SELECT show_chunks('" + schema + "." + tableName + "')");

          if (chunks.next()){
            //chunk table name format: _hyper_[tableid]_[chunkid]_chunk
            String fullTableName = chunks.getString(0);
            String chunkTableName = fullTableName.split(".")[1];
            String[] parts = chunkTableName.split("_");
            StringBuilder prefix = new StringBuilder();
            prefix.append("_");
            for (int i = 0; i < parts.length - 2; i++) {
              prefix.append(parts[i]);
            }
            prefix.append("_");
            hyperTablePrefix = prefix.toString();

          }
        }

        ResultSet hyperTableCount = statement.executeQuery("SELECT sum(n_tup_ins) from pg_stat_user_tables where" +
            " relname like '" + hyperTablePrefix + "%';");

        if(hyperTableCount.next()) {
          cnt1 = hyperTableCount.getLong(0);
          t1 = System.currentTimeMillis();
        }

        if (cnt2 == -1 || cnt1 < cnt2) {
          cnt2 = cnt1;
          startCount = cnt1;
          endCount = cnt1;
          regression = new SimpleRegression();
          samples = new HashMap<>();
          numSamples = 0;

        } else if (cnt1 > cnt2) {
          // Add to Linear Regression
          regression.addData(t1, cnt1);
          samples.put(t1, cnt1);
          // Increase number of samples
          numSamples += 1;
          if (numSamples > 2) {
            double rcvRate = regression.getSlope() * 1000;
            System.out.format("%d,%d,%d,%.0f\n", numSamples, t1, cnt1, rcvRate);
          } else {
            System.out.format("%d,%d,%d\n", numSamples, t1, cnt1);
          }

        } else if (cnt1 == cnt2 && numSamples > 0) {
          numSamples -= 1;
          // Remove the last sample
          regression.removeData(t2, cnt2);
          samples.remove(t2, cnt2);
          if (sendStdout) {
            System.out.println("Removing: " + t2 + "," + cnt2);
          }
          // Output Results
          long cnt = cnt2 - startCount;
          double rcvRate = regression.getSlope() * 1000;  // converting from ms to seconds

          if (numSamples > 5) {
            double rateStdErr = regression.getSlopeStdErr();
            if (sendStdout) {
              System.out.format("%d , %.2f, %.4f\n", cnt, rcvRate, rateStdErr);
            }
          } else if (numSamples >= 2) {
            if (sendStdout) {
              System.out.format("%d , %.2f\n", cnt, rcvRate);
            }
          } else {
            if (sendStdout) {
              System.out.println("Not enough samples to calculate rate. ");
            }
          }

          // Reset
          cnt1 = -1;
          cnt2 = -1;
          t1 = 0L;
          t2 = 0L;

        }

        cnt2 = cnt1;
        t2 = t1;

      } catch (UnsupportedOperationException | JSONException | SQLException e) {
        LOG.error("ERROR", e);

      }

    }

  }

  Timer timer;
  String connectionUrl;
  String user;
  String userpw;
  String schema;
  String tableName;
  String hyperTablePrefix;
  int sampleRateSec;
  boolean sendStdout;

  public TimescaleSqlMon(String connectionUrl, String schema, String tableName, int sampleRateSec, String user, String userpw, boolean sendStdout) {

    this.connectionUrl = connectionUrl;
    this.sampleRateSec = sampleRateSec;
    this.user = user;
    this.userpw = userpw;
    this.sendStdout = sendStdout;
    this.schema  = schema;
    this.tableName = tableName;
  }

  public void run() {
    try {

      timer = new Timer();
      timer.schedule(new TimescaleSqlMon.CheckCount(), 0, sampleRateSec * 1000);

    } catch (Exception e) {
      LOG.error("ERROR", e);
    }

  }

  public static void main(String[] args) {

    String connectionUrl = "";
    String username = "";   // default to empty string
    String password = "";  // default to empty string
    String schema = "";
    String tableName = "";
    int sampleRateSec = 5; // default to 5 seconds.
    Boolean sendStdout = true;

    LOG.info("Entering application.");
    int numargs = args.length;
    if (numargs != 3 && numargs != 4 && numargs != 6) {
      System.err.print("Usage: TimescaleSqlMon [connectionUrl] [schema] [tableName] (sampleRateSec) ((username) (password))  \n");
      System.err.println("Example: java -cp target/rttest.jar com.esri.rttest.mon.TimescaleSqlMon jdbc:postgresql://HostName:5432/dbName realtime safegraph 20 user pass");
    } else {
      connectionUrl = args[0];
      schema = args[1];
      tableName = args[2];

      if (numargs >= 4) {
        sampleRateSec = Integer.parseInt(args[3]);
      }

      if (numargs == 6) {
        username = args[4];
        password = args[5];
      }

      TimescaleSqlMon t = new TimescaleSqlMon(connectionUrl, schema, tableName, sampleRateSec, username, password, sendStdout);
      t.run();

    }

  }
}
