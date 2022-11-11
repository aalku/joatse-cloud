package org.aalku.joatse.cloud.tools.io;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PortRange {

	private Logger log = LoggerFactory.getLogger(PortRange.class);

	private int min;
	private int max;

	private boolean active = false;

	public int min() {
		return min;
	}

	public int max() {
		return max;
	}

	public void setup(String rangeString, String rangeProperty, Map<Integer, String> forbiddenPorts, Map<PortRange, String> forbidenRanges) throws Exception {
		if (rangeString == null || rangeString.isBlank()) {
			throw new Exception("You must configure '" + rangeProperty + "' property.");
		} else {
			Matcher m = Pattern.compile("^([1-9][0-9]*)-([1-9][0-9]*)$").matcher(rangeString);
			if (!m.matches()) {
				throw new Exception(
						"You must configure '" + rangeProperty + "' as a port range 'min-max'. For example: '10000-10500'.");
			}
			min = Integer.parseInt(m.group(1));
			max = Integer.parseInt(m.group(2));
			if (max > 65535) {
				max = 65535;
			}
			if (min > max) {
				throw new Exception(
						"You must configure '" + rangeProperty + "' as a port range 'min-max'. Min must be less or equal than max and both must be at most 65535.");
			}
			if (min < 1024) {
				log.warn(
						"Your '" + rangeProperty + "' enters the privileged port zone. Maybe you should use ports over 1023.");
			}
			rangeString = min + "-" + max;
			for (int p: forbiddenPorts.keySet()) {
				if (p >= min
						&& p <= max) {
					throw new Exception(
							"Port range '" + rangeProperty + "' overlaps with port '" + forbiddenPorts.get(p) + "'");
				}
			}
			for (PortRange r: forbidenRanges.keySet()) {
				if (r.active  && this.min <= r.max && this.max >= r.min) {
					throw new Exception(
							"Port range '" + rangeProperty + "' overlaps with port range '" + forbidenRanges.get(r) + "'");
				}
			}
			log.info("Port range '{}' = {}", rangeProperty, rangeString);
			active = true;
		}
	}

	public boolean isActive() {
		return active;
	}

}
