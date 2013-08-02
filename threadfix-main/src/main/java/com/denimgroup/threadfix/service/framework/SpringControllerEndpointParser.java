////////////////////////////////////////////////////////////////////////
//
//     Copyright (c) 2009-2013 Denim Group, Ltd.
//
//     The contents of this file are subject to the Mozilla Public License
//     Version 2.0 (the "License"); you may not use this file except in
//     compliance with the License. You may obtain a copy of the License at
//     http://www.mozilla.org/MPL/
//
//     Software distributed under the License is distributed on an "AS IS"
//     basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
//     License for the specific language governing rights and limitations
//     under the License.
//
//     The Original Code is ThreadFix.
//
//     The Initial Developer of the Original Code is Denim Group, Ltd.
//     Portions created by Denim Group, Ltd. are Copyright (C)
//     Denim Group, Ltd. All Rights Reserved.
//
//     Contributor(s): Denim Group, Ltd.
//
////////////////////////////////////////////////////////////////////////
package com.denimgroup.threadfix.service.framework;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

// TODO recognize String variables
// TODO support * values:
// from Spring documentation: Ant-style path patterns are supported (e.g. "/myPath/*.do").
public class SpringControllerEndpointParser implements EventBasedTokenizer {
	
	private Set<SpringControllerEndpoint> endpoints = new TreeSet<>();
	private int startLineNumber = 0, curlyBraceCount = 0;
	private boolean inClass = false;
	private String classEndpoint = null, currentMapping = null, rootFilePath = null, lastValue = null;
	private List<String> 
		classMethods  = new ArrayList<>(),
		methodMethods = new ArrayList<>(),
		currentParameters = new ArrayList<>();
		
	private Phase phase = Phase.ANNOTATION;
	private AnnotationState annotationState = AnnotationState.START;
	private MethodState methodState = MethodState.START;
	
	enum Phase {
		ANNOTATION, METHOD
	}
	
	enum AnnotationState {
		START, ARROBA, REQUEST_MAPPING, VALUE, METHOD, METHOD_MULTI_VALUE, ANNOTATION_END;
	}
	
	enum MethodState {
		START, ARROBA, REQUEST_PARAM, METHOD_BODY;
	}
	
	public static Set<SpringControllerEndpoint> parse(File file) {
		SpringControllerEndpointParser parser = new SpringControllerEndpointParser(file.getAbsolutePath());
		EventBasedTokenizerRunner.run(file, parser);
		return parser.endpoints;
	}
	
	private SpringControllerEndpointParser(String rootFilePath) {
		this.rootFilePath = rootFilePath;
	}
	
	@Override
	public void processToken(int type, int lineNumber, String stringValue) {
		switch (phase) {
			case ANNOTATION: parseAnnotation(type, lineNumber, stringValue); break;
			case METHOD:     parseMethod(type, lineNumber, stringValue);     break;
		}
	}
	
	private void parseMethod(int type, int lineNumber, String stringValue) {
		if (type == '{') {
			curlyBraceCount += 1;
		} else if (type == '}') {
			if (curlyBraceCount == 1) {
				addEndpoint(lineNumber);
				methodState = MethodState.START;
				phase = Phase.ANNOTATION;
			} else {
				curlyBraceCount -= 1;
			}
		}
	
		switch (methodState) {
			case START:
				if (type == '@') {
					methodState = MethodState.ARROBA;
				} else if (type == ')') {
					methodState = MethodState.METHOD_BODY;
				}
				break;
			case ARROBA:
				if (stringValue != null && 
						(stringValue.equals("RequestParam") || stringValue.equals("PathVariable"))) {
					methodState = MethodState.REQUEST_PARAM;
				} else {
					methodState = MethodState.START;
				}
				break;
			case REQUEST_PARAM:
				if (type == '"') {
					currentParameters.add(stringValue);
				} else if (type != ',' && type != ')') {
					lastValue = stringValue;
				} else if (type == ',') {
					currentParameters.add(lastValue);
					lastValue = null;
					methodState = MethodState.START;
				} else if (type == ')') {
					if (lastValue != null) {
						currentParameters.add(lastValue);
						lastValue = null;
					}
					methodState = MethodState.METHOD_BODY;
				}
				break;
			case METHOD_BODY:
				// TODO try to parse out parameters retrieved with HttpServletRequest.getParameter()
				break;
		}
	}

	private void parseAnnotation(int type, int lineNumber, String stringValue) {
		switch(annotationState) {
			case START: 
				if (type == '@') {
					annotationState = AnnotationState.ARROBA;
				} else if (stringValue != null && stringValue.equals("class")) {
					inClass = true;
				}
				break;
			case ARROBA:
				if (stringValue != null && stringValue.equals("RequestMapping")) {
					annotationState = AnnotationState.REQUEST_MAPPING;
				} else {
					annotationState = AnnotationState.START;
				}
				break;
			case REQUEST_MAPPING:
				if (stringValue != null && stringValue.equals("value")) {
					annotationState = AnnotationState.VALUE;
				} else if (stringValue != null && stringValue.equals("method")) {
					annotationState = AnnotationState.METHOD;
				} else if (type == '"') {
					// If it immediately starts with a quoted value, use it
					if (inClass) {
						currentMapping = stringValue;
						startLineNumber = lineNumber;
						annotationState = AnnotationState.ANNOTATION_END;
					} else {
						classEndpoint = stringValue;
						annotationState = AnnotationState.START;
					}
				} else if (type == ')'){
					annotationState = AnnotationState.ANNOTATION_END;
				}
				break;
			case VALUE:
				if (stringValue != null) {
					if (inClass) {
						currentMapping = stringValue;
						startLineNumber = lineNumber;
					} else {
						classEndpoint = stringValue;
					}
					annotationState = AnnotationState.REQUEST_MAPPING;
				}
				break;
			case METHOD:
				if (stringValue != null) {
					if (inClass) {
						methodMethods.add(stringValue);
					} else {
						classMethods.add(stringValue);
					}
					annotationState = AnnotationState.REQUEST_MAPPING;
				} else if (type == '{'){
					annotationState = AnnotationState.METHOD_MULTI_VALUE;
				}
				break;
			case METHOD_MULTI_VALUE:
				if (stringValue != null) {
					if (inClass) {
						methodMethods.add(stringValue);
					} else {
						classMethods.add(stringValue);
					}
				} else if (type == '}') {
					annotationState = AnnotationState.REQUEST_MAPPING;
				}
				break;
			case ANNOTATION_END:
				if (inClass) {
					annotationState = AnnotationState.START;
					phase = Phase.METHOD;
				} else {
					annotationState = AnnotationState.START;
				}
				break;
		}
	}

	private void addEndpoint(int endLineNumber) {
		if (classEndpoint != null) {
			currentMapping = classEndpoint + currentMapping;
		}
		
		
		
		// It's ok to add a default method here because we must be past the class-level annotation
		if (classMethods.isEmpty()) {
			classMethods.add("RequestMethod.GET");
		}
		
		if (methodMethods == null || methodMethods.isEmpty()) {
			methodMethods.addAll(classMethods);
		}
		
		endpoints.add(new SpringControllerEndpoint(rootFilePath, currentMapping, 
				methodMethods, currentParameters,
				startLineNumber, endLineNumber));
		currentMapping = null;
		methodMethods = new ArrayList<>();
		startLineNumber = -1;
		curlyBraceCount = 0;
		System.out.println(currentParameters);
		currentParameters = new ArrayList<>();
	}
	
}
