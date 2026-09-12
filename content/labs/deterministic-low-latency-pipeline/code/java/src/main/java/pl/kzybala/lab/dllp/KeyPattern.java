package pl.kzybala.lab.dllp;

public enum KeyPattern {
    UNIFORM {
        @Override
        public int keyFor(int id) {
            return id;
        }
    },
    HOT_KEY_SKEW {
        @Override
        public int keyFor(int id) {
            return (id % 5 == 0) ? id : 0; // 80% of events collapse onto key 0
        }
    };

    public abstract int keyFor(int id);
}
