package hipi.examples.downloader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;

/**
 * Treats keys as index into training array and value as the training vector.
 */
public class DownloaderRecordReader extends RecordReader<IntWritable, Text> {

  private boolean singletonEmit;
  private String urls;
  private long start_line;

  @Override
  public void initialize(InputSplit split, TaskAttemptContext context) throws IOException {
    FileSplit fileSplit = (FileSplit) split;
    Path path = fileSplit.getPath();
    FileSystem fileSystem = path.getFileSystem(context.getConfiguration());

    start_line = fileSplit.getStart();
    long num_lines = fileSplit.getLength();

    singletonEmit = false;

    BufferedReader reader = new BufferedReader(new InputStreamReader(fileSystem.open(path)));
    int i = 0;
    while (i < start_line && reader.readLine() != null) {
      i++;
    }

    urls = "";
    String line;
    for (i = 0; i < num_lines && (line = reader.readLine()) != null; i++) {
      urls += line + '\n';
    }

    reader.close();
  }


  /**
   * Get the progress within the split
   */
  @Override
  public float getProgress() {
    if (singletonEmit) {
      return 1.0f;
    } else {
      return 0.0f;
    }
  }

  @Override
  public void close() throws IOException {
    return;
  }

  @Override
  public IntWritable getCurrentKey() throws IOException, InterruptedException {
    return new IntWritable((int) start_line);
  }


  @Override
  public Text getCurrentValue() throws IOException, InterruptedException {
    return new Text(urls);
  }

  @Override
  public boolean nextKeyValue() throws IOException, InterruptedException {
    if (singletonEmit == false) {
      singletonEmit = true;
      return true;
    } else {
      return false;
    }
  }


}