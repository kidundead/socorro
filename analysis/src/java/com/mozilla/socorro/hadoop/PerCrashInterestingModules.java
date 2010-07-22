package com.mozilla.socorro.hadoop;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.MultiScanTableMapReduceUtil;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.reduce.LongSumReducer;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;

import com.mozilla.util.DateUtil;

/**
 * PerCrashInterestingModules will read crash report data in from HBase and count
 * the number of crashes at different levels (product, version, OS, signature, module, module_version).
 * 
 * This code is adapted based on David Baron's python script per-crash-interesting-modules.py.
 * 
 * @author Xavier Stevens
 *
 */
public class PerCrashInterestingModules implements Tool {

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PerCrashInterestingModules.class);
	
	private static final String NAME = "PerCrashInterestingModules";
	private Configuration conf;

	// HBase table and column names
	private static final String TABLE_NAME_CRASH_REPORTS = "crash_reports";
	private static final byte[] PROCESSED_DATA_BYTES = "processed_data".getBytes();
	private static final byte[] JSON_BYTES = "json".getBytes();
	
	// Configuration fields
	private static final String PRODUCT_FILTER = "product.filter";
	private static final String RELEASE_FILTER = "release.filter";
	private static final String CONDENSE = "condense";
	private static final String GROUP_BY_OS_VERSION = "group.by.os.version";
	private static final String START_DATE = "start.date";
	private static final String END_DATE = "end.date";
	private static final String START_TIME = "start.time";
	private static final String END_TIME = "end.time";
	private static final String ADDONS = "addons";
	private static final String SHOW_VERSIONS = "show.versions";
	
	// Crash JSON fields
	private static final String PRODUCT = "product";
	private static final String VERSION = "version";
	private static final String OS_NAME = "os_name";
	private static final String OS_VERSION = "os_version";
	private static final String LINUX = "Linux";
	private static final String SIGNATURE = "signature";
	private static final String REASON = "reason";
	private static final String DUMP = "dump";
	private static final String DATE_PROCESSED = "date_processed";
	private static final String MODULE_PATTERN = "Module|";
	
	private static final String KEY_DELIMITER = "\u0001";
	private static final String MODULE_INFO_DELIMITER = "\u0002";
	
	public static class PerCrashInterestingModulesMapper extends TableMapper<Text, LongWritable> {

		public enum ReportStats { JSON_PARSE_EXCEPTION, JSON_MAPPING_EXCEPTION, JSON_BYTES_NULL, DATE_PARSE_EXCEPTION }
		
		private Text outputKey;
		private LongWritable one;
		
		private ObjectMapper jsonMapper;
		
		private String productFilter;
		private String releaseFilter;
		private boolean groupByOsVersion;
		private boolean condense;
		private boolean addons;
		
		private Pattern dllPattern;
		private Pattern newlinePattern;
		private Pattern pipePattern;

		private SimpleDateFormat sdf;
		private long startTime;
		private long endTime;
		
		/* (non-Javadoc)
		 * @see org.apache.hadoop.mapreduce.Mapper#setup(org.apache.hadoop.mapreduce.Mapper.Context)
		 */
		public void setup(Context context) {
			outputKey = new Text();
			one = new LongWritable(1L);
			
			jsonMapper = new ObjectMapper();
			
			Configuration conf = context.getConfiguration();
			productFilter = conf.get(PRODUCT_FILTER);
			releaseFilter = conf.get(RELEASE_FILTER);
			groupByOsVersion = conf.getBoolean(GROUP_BY_OS_VERSION, false);
			condense = conf.getBoolean(CONDENSE, false);
			addons = conf.getBoolean(ADDONS, false);
			
			dllPattern = Pattern.compile("(\\S+)@0x[0-9a-fA-F]+$");
			newlinePattern = Pattern.compile("\n");
			pipePattern = Pattern.compile("\\|");
			
			sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			startTime = DateUtil.getTimeAtResolution(conf.getLong(START_TIME, 0), Calendar.DATE);
			endTime = DateUtil.getEndTimeAtResolution(conf.getLong(END_TIME, System.currentTimeMillis()), Calendar.DATE);
		}

		/* (non-Javadoc)
		 * @see org.apache.hadoop.mapreduce.Mapper#map(KEYIN, VALUEIN, org.apache.hadoop.mapreduce.Mapper.Context)
		 */
		@SuppressWarnings("unchecked")
		public void map(ImmutableBytesWritable key, Result result, Context context) throws InterruptedException, IOException {
			try {
				byte[] valueBytes = result.getValue(PROCESSED_DATA_BYTES, JSON_BYTES);
				if (valueBytes == null) {
					context.getCounter(ReportStats.JSON_BYTES_NULL).increment(1L);
					return;
				}
				String value = new String(valueBytes);
				// This is an untyped parse so the caller is expected to know the types
				Map<String,Object> crash = jsonMapper.readValue(value, new TypeReference<Map<String,Object>>() { });
				
				// Filter row if filter(s) are set and it doesn't match
				if (!StringUtils.isBlank(productFilter)) {
					if (crash.containsKey(PRODUCT) && !crash.get(PRODUCT).equals(productFilter)) {
						return;
					}
				} 
				if (!StringUtils.isBlank(releaseFilter)) {
					if (crash.containsKey(releaseFilter) && !crash.get(VERSION).equals(releaseFilter)) {
						return;
					}
				}
				
				// Set the value to the date
				String dateProcessed = (String)crash.get(DATE_PROCESSED);
				long crashTime = sdf.parse(dateProcessed).getTime();
				// Filter if the processed date is not within our window
				if (crashTime < startTime || crashTime > endTime) {
					return;
				}
				
				String osName = (String)crash.get(OS_NAME);
				if (osName == null) {
					osName = "Unknown OS";
					outputKey.set(osName);
					context.write(outputKey, one);
					return;
				}

				if (groupByOsVersion && !LINUX.equals(osName)) {
					String osVersion = (String)crash.get(OS_VERSION);
					if (osVersion == null) {
						osVersion = "Unknown OS Version";
					}
					osName = osName + " " + osVersion;
				}
				
				// Output so we count at OS level
				outputKey.set(osName);
				context.write(outputKey, one);
				
				String signame = (String)crash.get(SIGNATURE);
				if (!StringUtils.isBlank(signame)) {
					if (condense) {
						Matcher sigMatcher = dllPattern.matcher(signame);
						if (sigMatcher.find() && sigMatcher.groupCount() > 0) {
							signame = sigMatcher.group(1);
						}
					}
					
					signame = signame + "|" + crash.get(REASON);
				} else {
					signame = "(no signature)";
				}
				
				// Output so we count at OS->Signature level
				outputKey.set(osName + KEY_DELIMITER + signame);
				context.write(outputKey, one);
				
				if (addons) {
					List<Object> addons = (ArrayList<Object>)crash.get(ADDONS);
					if (addons != null) {
						for (Object addon : addons) {
							List<String> addonList = (ArrayList<String>)addon;
							String addonName = addonList.get(0);
							String version = addonList.get(1);
							
							// Output so we count at OS->Module level
							outputKey.set(osName + KEY_DELIMITER + KEY_DELIMITER + addonName);
							context.write(outputKey, one);
							
							// Output so we count at OS->Module->Version level
							outputKey.set(osName + KEY_DELIMITER + KEY_DELIMITER + addonName + MODULE_INFO_DELIMITER + version);
							context.write(outputKey, one);
							
							// Output so we count at OS->Signature->Module level
							outputKey.set(osName + KEY_DELIMITER + signame + KEY_DELIMITER + addonName);
							context.write(outputKey, one);
							
							// Output so we count at OS->Signature->Module->Version level
							outputKey.set(osName + KEY_DELIMITER + signame + KEY_DELIMITER + addonName + MODULE_INFO_DELIMITER + version);
							context.write(outputKey, one);
						}
					}
				} else {
					for (String dumpline : newlinePattern.split((String)crash.get(DUMP))) {
						if (dumpline.startsWith(MODULE_PATTERN)) {
							// module_str, libname, version, pdb, checksum, addrstart, addrend, unknown
							String[] dumplineSplits = pipePattern.split(dumpline);
							
							String moduleName;
							String version;
							if (osName.startsWith("Win")) {
								// we only have good version data on windows
								moduleName = dumplineSplits[1];
								version = dumplineSplits[2];
							} else {
								moduleName = dumplineSplits[1];
								version = dumplineSplits[4];
							}
							
							// Output so we count at OS->Module level
							outputKey.set(osName + KEY_DELIMITER + KEY_DELIMITER + moduleName);
							context.write(outputKey, one);
							
							// Output so we count at OS->Module->Version level
							outputKey.set(osName + KEY_DELIMITER + KEY_DELIMITER + moduleName + MODULE_INFO_DELIMITER + version);
							context.write(outputKey, one);
							
							// Output so we count at OS->Signature->Module level
							outputKey.set(osName + KEY_DELIMITER + signame + KEY_DELIMITER + moduleName);
							context.write(outputKey, one);
							
							// Output so we count at OS->Signature->Module->Version level
							outputKey.set(osName + KEY_DELIMITER + signame + KEY_DELIMITER + moduleName + MODULE_INFO_DELIMITER + version);
							context.write(outputKey, one);
						}
					}
				}
			} catch (JsonParseException e) {
				context.getCounter(ReportStats.JSON_PARSE_EXCEPTION).increment(1L);
			} catch (JsonMappingException e) {
				context.getCounter(ReportStats.JSON_MAPPING_EXCEPTION).increment(1L);
			} catch (ParseException e) {
				context.getCounter(ReportStats.DATE_PARSE_EXCEPTION).increment(1L);
			}
		}
		
	}

	/**
	 * Effectively a LongSumReducer with an added minimum count threshold before outputting the result
	 */
	public static class PerCrashInterestingModulesReducer extends Reducer<Text, LongWritable, Text, LongWritable> {
		
		private long minCount;
		private LongWritable outputValue;
		
		public void setup(Context context) {
			minCount = 10;
			outputValue = new LongWritable();
		}
		
		public void reduce(Text key, Iterable<LongWritable> values, Context context) throws InterruptedException, IOException {
			long sum = 0;
			Iterator<LongWritable> iter = values.iterator();
			while(iter.hasNext()) {
				sum += iter.next().get();
			}
			
			if (sum >= minCount) {
				outputValue.set(sum);
				context.write(key, outputValue);
			}
		}
	}	

	/**
	 * @param args
	 * @return
	 * @throws IOException
	 * @throws ParseException 
	 */
	public Job initJob(String[] args) throws IOException, ParseException {
		// Set both start/end time and start/stop row
		Calendar cal = Calendar.getInstance();
		
		SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
		SimpleDateFormat rowsdf = new SimpleDateFormat("yyMMdd");
		
		String startDate = conf.get(START_DATE);
		String endDate = conf.get(END_DATE);
		int startDateAsInt = 0;
		int endDateAsInt = 0;
		if (!StringUtils.isBlank(startDate)) {
			Date d = sdf.parse(startDate);
			conf.setLong(START_TIME, d.getTime());
			startDateAsInt = Integer.parseInt(rowsdf.format(d));
		} else {
			conf.setLong(START_TIME, cal.getTimeInMillis());
			startDateAsInt = Integer.parseInt(rowsdf.format(cal.getTime()));
		}
		if (!StringUtils.isBlank(endDate)) {
			Date d = sdf.parse(endDate);
			conf.setLong(END_TIME, d.getTime());
			endDateAsInt = Integer.parseInt(rowsdf.format(d));
		} else {
			conf.setLong(END_TIME, cal.getTimeInMillis());
			endDateAsInt = Integer.parseInt(rowsdf.format(cal.getTime()));
		}
		
		Job job = new Job(getConf());
		job.setJobName(NAME);
		job.setJarByClass(PerCrashInterestingModules.class);
		
		// input table configuration
		Scan[] scans = PerCrashCoreCount.generateScans(startDateAsInt, endDateAsInt);
		MultiScanTableMapReduceUtil.initMultiScanTableMapperJob(TABLE_NAME_CRASH_REPORTS, scans, PerCrashInterestingModulesMapper.class, Text.class, LongWritable.class, job);

		job.setCombinerClass(LongSumReducer.class);
		job.setReducerClass(LongSumReducer.class);
		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(LongWritable.class);
		
		FileOutputFormat.setOutputPath(job, new Path(args[0]));
		
		return job;
	}

	/**
	 * @return
	 */
	private static int printUsage() {
		System.out.println("Usage: " + NAME + " [generic-options] <output-path>");
		System.out.println();
		System.out.println("Configurable Properties:");
		System.out.println(PRODUCT_FILTER + "=<product-name>");
		System.out.println(RELEASE_FILTER + "=<release-version>");
		System.out.println(GROUP_BY_OS_VERSION + "=<true|false>");
		System.out.println(CONDENSE + "<true|false>");
		System.out.println(ADDONS + "=<true|false>");
		System.out.println(SHOW_VERSIONS + "=<true|false>");
		System.out.println(START_DATE + "=<yyyyMMdd>");
		System.out.println(END_DATE + "=<yyyyMMdd>");
		System.out.println();
		GenericOptionsParser.printGenericCommandUsage(System.out);
		return -1;
	}
	
	/* (non-Javadoc)
	 * @see org.apache.hadoop.util.Tool#run(java.lang.String[])
	 */
	public int run(String[] args) throws Exception {
		if (args.length != 1) {
			return printUsage();
		}
		
		int rc = -1;
		Job job = initJob(args);
		job.waitForCompletion(true);
		if (job.isSuccessful()) {
			rc = 0;
		}
		
		return rc;
	}

	/* (non-Javadoc)
	 * @see org.apache.hadoop.conf.Configurable#getConf()
	 */
	public Configuration getConf() {
		return this.conf;
	}

	/* (non-Javadoc)
	 * @see org.apache.hadoop.conf.Configurable#setConf(org.apache.hadoop.conf.Configuration)
	 */
	public void setConf(Configuration conf) {
		this.conf = conf;
	}
	
	/**
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
		int res = ToolRunner.run(new Configuration(), new PerCrashInterestingModules(), args);
		System.exit(res);
	}

}