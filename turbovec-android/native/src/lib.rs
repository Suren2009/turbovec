use std::panic::{self, AssertUnwindSafe};

use jni::objects::{JBooleanArray, JClass, JFloatArray, JObject, JString, JValue};
use jni::sys::{jint, jlong, jobject};
use jni::JNIEnv;

use turbovec_core::{SearchResults, TurboQuantIndex};

const ERR_ILLEGAL_ARGUMENT: &str = "java/lang/IllegalArgumentException";
const ERR_ILLEGAL_STATE: &str = "java/lang/IllegalStateException";
const ERR_IO: &str = "java/io/IOException";
const ERR_RUNTIME: &str = "java/lang/RuntimeException";
const SEARCH_RESULT_CLASS: &str = "com/turbovec/android/TurboVecIndex$SearchResult";

fn guard<T, F>(env: &mut JNIEnv<'_>, default: T, exception_class: &str, f: F) -> T
where
    F: FnOnce(&mut JNIEnv<'_>) -> Result<T, String>,
{
    match panic::catch_unwind(AssertUnwindSafe(|| f(env))) {
        Ok(Ok(value)) => value,
        Ok(Err(message)) => {
            let _ = env.throw_new(exception_class, message);
            default
        }
        Err(_) => {
            let _ = env.throw_new(exception_class, "native turbovec call panicked");
            default
        }
    }
}

fn guard_void<F>(env: &mut JNIEnv<'_>, exception_class: &str, f: F)
where
    F: FnOnce(&mut JNIEnv<'_>) -> Result<(), String>,
{
    guard(env, (), exception_class, f)
}

unsafe fn index_ref<'a>(handle: jlong) -> Result<&'a TurboQuantIndex, String> {
    if handle == 0 {
        return Err("native turbovec handle is null".to_string());
    }
    Ok(&*(handle as *const TurboQuantIndex))
}

unsafe fn index_mut<'a>(handle: jlong) -> Result<&'a mut TurboQuantIndex, String> {
    if handle == 0 {
        return Err("native turbovec handle is null".to_string());
    }
    Ok(&mut *(handle as *mut TurboQuantIndex))
}

fn read_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<String, String> {
    env.get_string(value)
        .map(|s| s.into())
        .map_err(|e| e.to_string())
}

fn read_float_array(env: &mut JNIEnv<'_>, array: &JFloatArray<'_>) -> Result<Vec<f32>, String> {
    let len = env.get_array_length(array).map_err(|e| e.to_string())? as usize;
    let mut values = vec![0.0; len];
    env.get_float_array_region(array, 0, &mut values)
        .map_err(|e| e.to_string())?;
    Ok(values)
}

fn read_optional_mask(
    env: &mut JNIEnv<'_>,
    mask: JObject<'_>,
    expected_len: usize,
) -> Result<Option<Vec<bool>>, String> {
    if mask.is_null() {
        return Ok(None);
    }

    let mask = JBooleanArray::from(mask);
    let len = env.get_array_length(&mask).map_err(|e| e.to_string())? as usize;
    if len != expected_len {
        return Err(format!(
            "mask length {len} does not match index size {expected_len}",
        ));
    }

    let mut raw = vec![0; len];
    env.get_boolean_array_region(&mask, 0, &mut raw)
        .map_err(|e| e.to_string())?;
    Ok(Some(raw.into_iter().map(|value| value != 0).collect()))
}

fn ensure_non_negative(name: &str, value: jint) -> Result<usize, String> {
    if value < 0 {
        return Err(format!("{name} must be non-negative"));
    }
    Ok(value as usize)
}

fn to_jint(name: &str, value: usize) -> Result<jint, String> {
    if value > jint::MAX as usize {
        return Err(format!("{name} is too large for Android JNI"));
    }
    Ok(value as jint)
}

fn make_search_result(env: &mut JNIEnv<'_>, result: SearchResults) -> Result<jobject, String> {
    let scores_len = to_jint("scores length", result.scores.len())?;
    let indices_len = to_jint("indices length", result.indices.len())?;
    let query_count = to_jint("query count", result.nq)?;
    let effective_k = to_jint("k", result.k)?;

    let scores = env.new_float_array(scores_len).map_err(|e| e.to_string())?;
    env.set_float_array_region(&scores, 0, &result.scores)
        .map_err(|e| e.to_string())?;

    let indices = env.new_long_array(indices_len).map_err(|e| e.to_string())?;
    let indices_values: Vec<jlong> = result.indices.into_iter().map(|idx| idx as jlong).collect();
    env.set_long_array_region(&indices, 0, &indices_values)
        .map_err(|e| e.to_string())?;

    let scores_object = JObject::from(scores);
    let indices_object = JObject::from(indices);
    let object = env
        .new_object(
            SEARCH_RESULT_CLASS,
            "([F[JII)V",
            &[
                JValue::Object(&scores_object),
                JValue::Object(&indices_object),
                JValue::Int(query_count),
                JValue::Int(effective_k),
            ],
        )
        .map_err(|e| e.to_string())?;

    Ok(object.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_turbovec_android_TurboVecIndex_nativeNew(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    dim: jint,
    bit_width: jint,
) -> jlong {
    guard(&mut env, 0, ERR_ILLEGAL_ARGUMENT, |_env| {
        let dim = ensure_non_negative("dim", dim)?;
        let bit_width = ensure_non_negative("bitWidth", bit_width)?;
        let index = TurboQuantIndex::new(dim, bit_width).map_err(|e| e.to_string())?;
        Ok(Box::into_raw(Box::new(index)) as jlong)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_turbovec_android_TurboVecIndex_nativeNewLazy(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    bit_width: jint,
) -> jlong {
    guard(&mut env, 0, ERR_ILLEGAL_ARGUMENT, |_env| {
        let bit_width = ensure_non_negative("bitWidth", bit_width)?;
        let index = TurboQuantIndex::new_lazy(bit_width).map_err(|e| e.to_string())?;
        Ok(Box::into_raw(Box::new(index)) as jlong)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_turbovec_android_TurboVecIndex_nativeLoad(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
) -> jlong {
    guard(&mut env, 0, ERR_IO, |env| {
        let path = read_string(env, &path)?;
        let index = TurboQuantIndex::load(path).map_err(|e| e.to_string())?;
        Ok(Box::into_raw(Box::new(index)) as jlong)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_turbovec_android_TurboVecIndex_nativeFree(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    guard_void(&mut env, ERR_RUNTIME, |_env| {
        if handle != 0 {
            unsafe {
                drop(Box::from_raw(handle as *mut TurboQuantIndex));
            }
        }
        Ok(())
    });
}

#[no_mangle]
pub extern "system" fn Java_com_turbovec_android_TurboVecIndex_nativeAdd(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    vectors: JFloatArray<'_>,
    dim: jint,
) {
    guard_void(&mut env, ERR_ILLEGAL_ARGUMENT, |env| {
        let dim = ensure_non_negative("dim", dim)?;
        let vectors = read_float_array(env, &vectors)?;
        let index = unsafe { index_mut(handle)? };
        index.add_2d(&vectors, dim).map_err(|e| e.to_string())
    });
}

#[no_mangle]
pub extern "system" fn Java_com_turbovec_android_TurboVecIndex_nativeSearch(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    queries: JFloatArray<'_>,
    k: jint,
    mask: JObject<'_>,
) -> jobject {
    guard(
        &mut env,
        std::ptr::null_mut(),
        ERR_ILLEGAL_ARGUMENT,
        |env| {
            let k = ensure_non_negative("k", k)?;
            let queries = read_float_array(env, &queries)?;
            let index = unsafe { index_ref(handle)? };
            let mask = read_optional_mask(env, mask, index.len())?;
            let result = index.search_with_mask(&queries, k, mask.as_deref());
            make_search_result(env, result)
        },
    )
}

#[no_mangle]
pub extern "system" fn Java_com_turbovec_android_TurboVecIndex_nativeWrite(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    path: JString<'_>,
) {
    guard_void(&mut env, ERR_IO, |env| {
        let path = read_string(env, &path)?;
        let index = unsafe { index_ref(handle)? };
        index.write(path).map_err(|e| e.to_string())
    });
}

#[no_mangle]
pub extern "system" fn Java_com_turbovec_android_TurboVecIndex_nativePrepare(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    guard_void(&mut env, ERR_RUNTIME, |_env| {
        let index = unsafe { index_ref(handle)? };
        index.prepare();
        Ok(())
    });
}

#[no_mangle]
pub extern "system" fn Java_com_turbovec_android_TurboVecIndex_nativeSwapRemove(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    index: jint,
) -> jint {
    guard(&mut env, 0, ERR_ILLEGAL_ARGUMENT, |_env| {
        let slot = ensure_non_negative("index", index)?;
        let index = unsafe { index_mut(handle)? };
        to_jint("moved index", index.swap_remove(slot))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_turbovec_android_TurboVecIndex_nativeSize(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jint {
    guard(&mut env, 0, ERR_ILLEGAL_STATE, |_env| {
        let index = unsafe { index_ref(handle)? };
        to_jint("index size", index.len())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_turbovec_android_TurboVecIndex_nativeDim(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jint {
    guard(&mut env, 0, ERR_ILLEGAL_STATE, |_env| {
        let index = unsafe { index_ref(handle)? };
        to_jint("index dim", index.dim())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_turbovec_android_TurboVecIndex_nativeBitWidth(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jint {
    guard(&mut env, 0, ERR_ILLEGAL_STATE, |_env| {
        let index = unsafe { index_ref(handle)? };
        to_jint("index bit width", index.bit_width())
    })
}
