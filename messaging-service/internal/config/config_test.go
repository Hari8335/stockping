package config

import "testing"

func TestPort(t *testing.T) {
	for _, tc := range []struct{ input, want string }{{"", "8083"}, {"9000", "9000"}, {"1", "1"}, {"65535", "65535"}} {
		t.Run(tc.input, func(t *testing.T) {
			got, err := Port(tc.input)
			if err != nil || got != tc.want {
				t.Fatalf("Port(%q) = %q, %v; want %q", tc.input, got, err, tc.want)
			}
		})
	}
	for _, value := range []string{"0", "-1", "65536", "abc", " 8083", "8083\n"} {
		if _, err := Port(value); err == nil {
			t.Errorf("Port(%q) accepted invalid configuration", value)
		}
	}
}
