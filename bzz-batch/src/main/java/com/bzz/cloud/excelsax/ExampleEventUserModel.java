package com.bzz.cloud.excelsax;


import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.*;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.BuiltinFormats;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.SharedStringsTable;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTRst;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLReaderFactory;

public class ExampleEventUserModel {
	
	private static StylesTable stylesTable;
	private  List<List<String>> list = new ArrayList<List<String>>();
	private static final Log log = LogFactory.getLog(ExampleEventUserModel.class);
	/**
	 * 处理一个sheet
	 * @param filename
	 * @throws Exception
	 */
	public List<List<String>> processOneSheet(String filename) throws Exception {
		
		
		long startTime = System.currentTimeMillis();
		Date date1 = new Date();
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
		FileInputStream fis = new FileInputStream(filename);
		
		OPCPackage pkg = OPCPackage.open(fis);
		XSSFReader r = new XSSFReader( pkg );
		stylesTable = r.getStylesTable();
		SharedStringsTable sst = r.getSharedStringsTable();
		
		XMLReader parser = fetchSheetParser(sst);
		
		// Seems to either be rId# or rSheet#
		InputStream sheet2 = r.getSheet("rId1");
		InputSource sheetSource = new InputSource(sheet2);
		parser.parse(sheetSource);
		sheet2.close();
		long endTime = System.currentTimeMillis();
		Date date2 = new Date();
		System.out.println("");
		System.out.println("使用poi sax方式解析文件"+filename+"，开始时间："+sdf.format(date1)+"，结束时间："+sdf.format(date2)+"，共耗时"+((endTime-startTime)/1000)+"秒(毫秒："+(endTime-startTime)+"),共读取execl数据 :"+list.size()+"行");
		
		return list;
	}
	
	/**
	 * 处理所有sheet
	 * @param filename
	 * @throws Exception
	 */
	public void processAllSheets(String filename) throws Exception {
		
		OPCPackage pkg = OPCPackage.open(filename);
		XSSFReader r = new XSSFReader( pkg );
		SharedStringsTable sst = r.getSharedStringsTable();
		
		XMLReader parser = fetchSheetParser(sst);
		
		Iterator<InputStream> sheets = r.getSheetsData();
		while(sheets.hasNext()) {
			System.out.println("Processing new sheet:\n");
			InputStream sheet = sheets.next();
			InputSource sheetSource = new InputSource(sheet);
			parser.parse(sheetSource);
			sheet.close();
			System.out.println("");
		}
	}
	
	/**
	 * 获取解析器
	 * @param sst
	 * @return
	 * @throws SAXException
	 */
	public XMLReader fetchSheetParser(SharedStringsTable sst) throws SAXException {
		XMLReader parser =
				XMLReaderFactory.createXMLReader(
						"org.apache.xerces.parsers.SAXParser"
				);
		ContentHandler handler = new SheetHandler(sst);
		parser.setContentHandler(handler);
		return parser;
	}
	
	/**
	 * 自定义解析处理器
	 * See org.xml.sax.helpers.DefaultHandler javadocs
	 */
	private  class SheetHandler extends DefaultHandler {
		
		private SharedStringsTable sst;
		private String lastContents;
		private boolean nextIsString;
		
		/*private List<String> rowlist = new ArrayList<String>();*/
		private List<String> rowlist ;
		private int curRow = 0;
		private int curCol = 0;
		
		//定义前一个元素和当前元素的位置，用来计算其中空的单元格数量，如A6和A8等
		private String preRef = null, ref = null;
		//定义该文档一行最大的单元格数，用来补全一行最后可能缺失的单元格
		private String maxRef = null;
		
		private CellDataType nextDataType = CellDataType.SSTINDEX;
		private final DataFormatter formatter = new DataFormatter();
		private short formatIndex;
		private String formatString;
		
		//用一个enum表示单元格可能的数据类型
//		enum CellDataType{
//			BOOL, ERROR, FORMULA, INLINESTR, SSTINDEX, NUMBER, DATE, NULL
//		}
		
		private SheetHandler(SharedStringsTable sst) {
			this.sst = sst;
		}
		
		/**
		 * 解析一个element的开始时触发事件
		 */
		public void startElement(String uri, String localName, String name,
		                         Attributes attributes) {
			
			// c => cell
			if(name.equals("c")) {
				//前一个单元格的位置
				if(preRef == null){
					preRef = attributes.getValue("r");
				}else{
					preRef = ref;
				}
				//当前单元格的位置
				ref = attributes.getValue("r");
				
				this.setNextDataType(attributes);
				
				// Figure out if the value is an index in the SST
				String cellType = attributes.getValue("t");
				nextIsString = cellType != null && cellType.equals("s");
				
			}
			// Clear contents cache
			lastContents = "";
		}
		
		/**
		 * 根据element属性设置数据类型
		 * @param attributes
		 */
		public void setNextDataType(Attributes attributes){
			
			nextDataType = CellDataType.NUMBER;
			formatIndex = -1;
			formatString = null;
			String cellType = attributes.getValue("t");
			String cellStyleStr = attributes.getValue("s");
			if ("b".equals(cellType)){
				nextDataType = CellDataType.BOOL;
			}else if ("e".equals(cellType)){
				nextDataType = CellDataType.ERROR;
			}else if ("inlineStr".equals(cellType)){
				nextDataType = CellDataType.INLINESTR;
			}else if ("s".equals(cellType)){
				nextDataType = CellDataType.SSTINDEX;
			}else if ("str".equals(cellType)){
				nextDataType = CellDataType.FORMULA;
			}/*else if(null == cellType){
				nextDataType = CellDataType.NULL; //其它值设置为字符串：string
			}*/
			if (cellStyleStr != null){
				int styleIndex = Integer.parseInt(cellStyleStr);
				XSSFCellStyle style = stylesTable.getStyleAt(styleIndex);
				formatIndex = style.getDataFormat();
				formatString = style.getDataFormatString();
				if ("m/d/yy" == formatString){
					nextDataType = CellDataType.DATE;
					//full format is "yyyy-MM-dd hh:mm:ss.SSS";
					formatString = "yyyy-MM-dd";
				}
				if (formatString == null){
					nextDataType = CellDataType.NULL;
					formatString = BuiltinFormats.getBuiltinFormat(formatIndex);
				}
			}
		}
		
		/**
		 * 解析一个element元素结束时触发事件
		 */
		public void endElement(String uri, String localName, String name) {
			// Process the last contents as required.
			// Do now, as characters() may be called more than once
			if(nextIsString) {
				int idx = Integer.parseInt(lastContents);
				CTRst entryAt = sst.getEntryAt(idx);
				
				lastContents = new XSSFRichTextString(sst.getEntryAt(idx)).toString();
				nextIsString = false;
			}
			
			// v => contents of a cell
			// Output after we've seen the string contents
			if (name.equals("v")) {
				
				String value = this.getDataValue(lastContents.trim(), "");
				//补全单元格之间的空单元格
				if(!ref.equals(preRef)){
					int len = countNullCell(ref, preRef);
					for(int i=0;i<len;i++){
						if(rowlist == null ){
							rowlist = new ArrayList<String>();
						}
						rowlist.add(curCol, "");
						curCol++;
					}
				}
				if(rowlist == null ){
					rowlist = new ArrayList<String>();
				}
				rowlist.add(curCol, value);
				curCol++;
			}else {
				//如果标签名称为 row，这说明已到行尾，调用 optRows() 方法
				if (name.equals("row")) {
					String value = "";
					//默认第一行为表头，以该行单元格数目为最大数目
					if(curRow == 0){
						maxRef = ref;
					}
					
					//补全一行尾部可能缺失的单元格
					if(maxRef != null && ref !=null){
						int len = countNullCell(maxRef, ref);
						for(int i=0;i<=len;i++){
							if(rowlist == null ){
								rowlist = new ArrayList<String>();
							}
							rowlist.add(curCol, "");
							curCol++;
						}
					}
					
					curRow++;
					//一行的末尾重置一些数据
//					if("row".equals(name)){
//						list.add(rowlist);
//						//rowlist.clear();
//						//rowlist = null;
//					}
					
					list.add(rowlist);
					
					rowlist = null;
					
					//rowlist.clear();
					curCol = 0;
					preRef = null;
					ref = null;
				}
			}
		}
		
		/**
		 * 根据数据类型获取数据
		 * @param value
		 * @param thisStr
		 * @return
		 */
		public String getDataValue(String value, String thisStr)
		
		{
			switch (nextDataType)
			{
				//这几个的顺序不能随便交换，交换了很可能会导致数据错误
				case BOOL:
					char first = value.charAt(0);
					thisStr = first == '0' ? "FALSE" : "TRUE";
					break;
				case ERROR:
					thisStr = "\"ERROR:" + value.toString() + '"';
					break;
				case FORMULA:
					thisStr = '"' + value.toString() + '"';
					break;
				case INLINESTR:
					XSSFRichTextString rtsi = new XSSFRichTextString(value.toString());
					thisStr = rtsi.toString();
					rtsi = null;
					break;
				case SSTINDEX:
					String sstIndex = value.toString();
					thisStr = value.toString();
					break;
				case NUMBER:
					if (formatString != null){
						thisStr = formatter.formatRawCellContents(Double.parseDouble(value), formatIndex, formatString).trim();
					}else{
						thisStr = value;
					}
					thisStr = thisStr.replace("_", "").trim();
					break;
				case DATE:
					try{
						thisStr = formatter.formatRawCellContents(Double.parseDouble(value), formatIndex, formatString);
					}catch(NumberFormatException ex){
						thisStr = value.toString();
					}
					thisStr = thisStr.replace(" ", "");
					break;
				default:
					thisStr = "";
					break;
			}
			return thisStr;
		}
		
		/**
		 * 获取element的文本数据
		 */
		public void characters(char[] ch, int start, int length) {
			lastContents += new String(ch, start, length);
		}
		
		/**
		 * 计算两个单元格之间的单元格数目(同一行)
		 * @param ref
		 * @param preRef
		 * @return
		 */
		public int countNullCell(String ref, String preRef){
			//excel2007最大行数是1048576，最大列数是16384，最后一列列名是XFD
			String xfd = ref.replaceAll("\\d+", "");
			String xfd_1 = preRef.replaceAll("\\d+", "");
			
			xfd = fillChar(xfd, 3, '@', true);
			xfd_1 = fillChar(xfd_1, 3, '@', true);
			
			char[] letter = xfd.toCharArray();
			char[] letter_1 = xfd_1.toCharArray();
			int res = (letter[0]-letter_1[0])*26*26 + (letter[1]-letter_1[1])*26 + (letter[2]-letter_1[2]);
			return res-1;
		}
		
		/**
		 * 字符串的填充
		 * @param str
		 * @param len
		 * @param let
		 * @param isPre
		 * @return
		 */
		String fillChar(String str, int len, char let, boolean isPre){
			int len_1 = str.length();
			if(len_1 <len){
				if(isPre){
					for(int i=0;i<(len-len_1);i++){
						str = let+str;
					}
				}else{
					for(int i=0;i<(len-len_1);i++){
						str = str+let;
					}
				}
			}
			return str;
		}
	}
	
	static BufferedWriter writer = null;
	
	public static void main(String[] args) throws Exception {
		ExampleEventUserModel example = new ExampleEventUserModel();
		String str = "2018-08";
		String filename = "D:\\test\\"+str+".xlsx ";
		System.out.println("-- 程序开始 --");
		long time_1 = System.currentTimeMillis();
		try{
			//writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream("D:\\"+str+".csv")));*//*
			List<List<String>> lists = example.processOneSheet(filename);
		}finally{
			//writer.close();
		}
		long time_2 = System.currentTimeMillis();
		System.out.println("-- 程序结束 --");
		System.out.println("-- 耗时 --"+(time_2 - time_1)+"ms");
	}
	
	
}