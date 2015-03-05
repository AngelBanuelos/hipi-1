package hipi.examples.covariance;

import hipi.image.FloatImage;
import hipi.image.ImageHeader;
import hipi.imagebundle.mapreduce.ImageBundleInputFormat;
import hipi.imagebundle.mapreduce.output.BinaryOutputFormat;
import hipi.image.ImageHeader.ImageType;

import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;

import java.io.IOException;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Iterator;

public class Covariance extends Configured implements Tool {

  public static final int N = 48; //patch size
  public static final float sigma = 10; //used in gaussian function

  public static class MeanMap extends Mapper<ImageHeader, FloatImage, IntWritable, FloatImage> {
    @Override
    public void map(ImageHeader key, FloatImage value, Context context) throws IOException,
        InterruptedException {
      if (value != null && value.getWidth() > N && value.getHeight() > N) {
        context.write(new IntWritable(0), generateMeanImage(value, 10, 10));
      }
    }

    //computes the mean of the 100 randomly sampled patches in each image, returns a FloatImage storing this mean
    private FloatImage generateMeanImage(FloatImage input, int xPatchCount, int yPatchCount) {
      FloatImage mean = new FloatImage(N, N, 1);
      for (int i = 0; i < xPatchCount; i++) {
        int x = (input.getWidth() - N) * i / xPatchCount;
        for (int j = 0; j < yPatchCount; j++) {
          int y = (input.getHeight() - N) * j / yPatchCount;
          FloatImage patch = input.crop(x, y, N, N).convert(FloatImage.RGB2GRAY);
          mean.add(patch);
        }
      }
      mean.scale((float) (1.0 / (xPatchCount * yPatchCount)));
      return mean;
    }
  }

  public static class MeanReduce extends Reducer<IntWritable, FloatImage, IntWritable, FloatImage> {

    @Override
    public void reduce(IntWritable key, Iterable<FloatImage> values, Context context)
        throws IOException, InterruptedException {

      FloatImage mean = new FloatImage(N, N, 1);
      int total = 0;
      for (FloatImage val : values) {
        mean.add(val);
        total++;
      }
      if (total > 0) {
        mean.scale(1.0f / total);
        context.write(key, mean);
      }
    }
  }

  public static class CovarianceMap extends
      Mapper<ImageHeader, FloatImage, IntWritable, FloatImage> {

    float[] gaussianArray;
    float[] mean;

    @Override
    public void setup(Context job) {
      //Create a normalized gaussian array with standard deviation of 10 pixels for patch masking
      try {
        gaussianArray = new float[N * N];
        float gaussianSum = 0;
        for (int i = 0; i < N; i++) {
          for (int j = 0; j < N; j++) {
            //two-dimensional gaussian function
            gaussianSum +=
                gaussianArray[i * N + j] =
                    (float) Math.exp(-((i - N / 2) * (i - N / 2) / (sigma * sigma) + (j - N / 2)
                        * (j - N / 2) / (sigma * sigma)));
          }
        }
        for (int i = 0; i < N; i++) {
          for (int j = 0; j < N; j++) {
            //normalize
            gaussianArray[i * N + j] *= (N * N) / gaussianSum;
          }
        }
        URI[] files = new URI[1];
        if (job.getCacheFiles() != null) {
          files = job.getCacheFiles();
        } else {
          System.err.println("cache files null...");
        }

        //get mean from previously run mean job
        Path cacheFilePath = new Path(files[0].toString());
        FSDataInputStream dis = FileSystem.get(job.getConfiguration()).open(cacheFilePath);
        dis.skip(4);
        FloatImage image = new FloatImage();
        image.readFields(dis);
        mean = image.getData();
      } catch (IOException ioe) {
        System.err.println(ioe);
      }
    }

    @Override
    public void map(ImageHeader key, FloatImage value, Context context) throws IOException,
        InterruptedException {
      if (value != null && value.getWidth() > N && value.getHeight() > N) {
        //holds 100 patches as they are collected from the image
        float[][] patchArray = new float[100][N * N];
        
        //generates pre-processed patches and stores them in patchArray (same patches used to calculate mean)
        for (int i = 0; i < 10; i++) {
          int x = (value.getWidth() - N) * i / 10;
          for (int j = 0; j < 10; j++) {
            int y = (value.getHeight() - N) * j / 10;
            FloatImage patch = value.crop(x, y, N, N).convert(FloatImage.RGB2GRAY);
            float[] pels = patch.getData();
            for (int k = 0; k < N * N; k++) {
              //subtract average grey level from pixels, mask with gaussian value
              patchArray[i * 10 + j][k] = (pels[k] - mean[k]) * gaussianArray[k];
            }
          }
        }
        //stores covariance
        float[] covarianceArray = new float[N * N * N * N];
        for (int i = 0; i < N * N; i++) {
          for (int j = 0; j < N * N; j++) {
            covarianceArray[i * N * N + j] = 0;
            for (int k = 0; k < 100; k++) {
              //covariance function - generate covariance from patches
              covarianceArray[i * N * N + j] += patchArray[k][i] * patchArray[k][j];
            }
          }
        }
        context.write(new IntWritable(0), new FloatImage(N * N, N * N, 1, covarianceArray));
      }
    }
  }

  public static class CovarianceReduce extends
      Reducer<IntWritable, FloatImage, IntWritable, FloatImage> {

    @Override
    public void reduce(IntWritable key, Iterable<FloatImage> values, Context context)
        throws IOException, InterruptedException {
      //combine partial covariances from patches into global covariance
      FloatImage cov = new FloatImage(N * N, N * N, 1);

      for (FloatImage val : values) {
        cov.add(val);
      }

      context.write(key, cov);
    }
  }

  public static void rmdir(String path, Configuration conf) throws IOException {
    Path output_path = new Path(path);
    FileSystem fileSystem = FileSystem.get(conf);
    if (fileSystem.exists(output_path)) {
      fileSystem.delete(output_path, true);
    }
  }

  public static void mkdir(String path, Configuration conf) throws IOException {
    Path output_path = new Path(path);
    FileSystem fileSystem = FileSystem.get(conf);
    if (!fileSystem.exists(output_path))
      fileSystem.mkdirs(output_path);
  }

  public int runMeanCompute(String[] args) throws Exception {


    Job job = Job.getInstance();
    job.setInputFormatClass(ImageBundleInputFormat.class);
    job.setJarByClass(Covariance.class);
    job.setOutputKeyClass(IntWritable.class);
    job.setOutputValueClass(FloatImage.class);

    job.setMapperClass(MeanMap.class);
    job.setCombinerClass(MeanReduce.class);
    job.setReducerClass(MeanReduce.class);

    String inputFileType = args[2];
    if (inputFileType.equals("hib"))
      job.setInputFormatClass(ImageBundleInputFormat.class);
    else {
      System.out.println("Usage: covariance <inputdir> <outputdir> <filetype>");
      System.exit(0);
    }
    job.setOutputFormatClass(BinaryOutputFormat.class);
    job.getConfiguration().setBoolean("mapreduce.map.output.compress", true);
    job.setSpeculativeExecution(true);
    FileInputFormat.setInputPaths(job, new Path(args[0]));
    mkdir(args[1], job.getConfiguration());
    rmdir(args[1] + "/mean-output/", job.getConfiguration());
    FileOutputFormat.setOutputPath(job, new Path(args[1] + "/mean-output/"));

    boolean success = job.waitForCompletion(true);
    return success ? 0 : 1;
  }

  public int runCovariance(String[] args) throws Exception {
    Job job = Job.getInstance();
    job.setJarByClass(Covariance.class);


    job.addCacheFile(new URI("hdfs://" + args[1] + "/mean-output/part-r-00000"));

    job.setOutputKeyClass(IntWritable.class);
    job.setOutputValueClass(FloatImage.class);

    job.setMapperClass(CovarianceMap.class);
    job.setCombinerClass(CovarianceReduce.class);
    job.setReducerClass(CovarianceReduce.class);

    String inputFileType = args[2];
    if (inputFileType.equals("hib"))
      job.setInputFormatClass(ImageBundleInputFormat.class);
    else {
      System.out.println("Usage: covariance <inputdir> <outputdir> <filetype>");
      System.exit(0);
    }
    job.setOutputFormatClass(BinaryOutputFormat.class);
    job.getConfiguration().setBoolean("mapreduce.map.output.compress", true);
    job.setSpeculativeExecution(true);


    FileInputFormat.setInputPaths(job, new Path(args[0]));
    mkdir(args[1], job.getConfiguration());
    rmdir(args[1] + "/covariance-output/", job.getConfiguration());
    FileOutputFormat.setOutputPath(job, new Path(args[1] + "/covariance-output/"));

    boolean success = job.waitForCompletion(true);
    return success ? 0 : 1;
  }

  public int run(String[] args) throws Exception {
    if (args.length < 3) {
      System.out.println("Number of arguments is incorrect.");
      System.out.println("Expecting 3 arguments. Received " + args.length + ".");
      System.out.println("Usage: covariance <inputdir> <outputdir> <filetype>");
      System.exit(0);
    }

    int success = runMeanCompute(args);
    if (success == 1)
      return 1;
    return runCovariance(args);
  }

  public static void main(String[] args) throws Exception {
    ToolRunner.run(new Covariance(), args);
    System.exit(0);
  }

}