#version 300 es
precision highp usampler2D;
precision highp sampler2D;

#define POINTY_CAPS
#define FIX_TWISTING

const vec3  light_top_dir = vec3(-0.4574957, 0.4574957, 0.7624929);
const float light_top_diffuse = 0.6 * 0.8;
const float light_top_specular = 0.6 * 0.125;
const float light_top_shininess = 20.0;
const vec3  light_front_dir = vec3(0.6985074, 0.1397015, 0.6985074);
const float light_front_diffuse = 0.6 * 0.3;
const float ambient = 0.3;
const float emission = 0.15;
const vec3 UP = vec3(0, 0, 1);

uniform mat4 view_matrix;
uniform mat4 projection_matrix;
uniform vec3 camera_position;
uniform int instance_offset;

uniform sampler2D position_tex;
uniform sampler2D height_width_angle_tex;
uniform sampler2D color_tex;
uniform usampler2D segment_index_tex;

layout(location = 0) in float vertex_id_float;
out vec3 color;

vec3 decode_color(float color) {
  int c = int(round(color));
  int r = (c >> 16) & 0xFF;
  int g = (c >> 8) & 0xFF;
  int b = (c >> 0) & 0xFF;
  float f = 1.0 / 255.0;
  return f * vec3(r, g, b);
}

float lighting(vec3 eye_position, vec3 eye_normal) {
  float top_diffuse = light_top_diffuse * max(dot(eye_normal, light_top_dir), 0.0);
  float front_diffuse = light_front_diffuse * max(dot(eye_normal, light_front_dir), 0.0);
  float top_specular = light_top_specular * pow(max(dot(-normalize(eye_position), reflect(-light_top_dir, eye_normal)), 0.0), light_top_shininess);
  return ambient + top_diffuse + front_diffuse + top_specular + emission;
}

ivec2 tex_coord(sampler2D sampler, int id) {
  ivec2 tex_size = textureSize(sampler, 0);
  return (tex_size.y == 1) ? ivec2(id, 0) : ivec2(id % tex_size.x, id / tex_size.x);
}

ivec2 tex_coord_u(usampler2D sampler, int id) {
  ivec2 tex_size = textureSize(sampler, 0);
  return (tex_size.y == 1) ? ivec2(id, 0) : ivec2(id % tex_size.x, id / tex_size.x);
}

void main() {
  int vertex_id = int(vertex_id_float);
  int id_a = int(texelFetch(segment_index_tex, tex_coord_u(segment_index_tex, gl_InstanceID + instance_offset), 0).r);
  int id_b = id_a + 1;
  vec3 pos_a = texelFetch(position_tex, tex_coord(position_tex, id_a), 0).xyz;
  vec3 pos_b = texelFetch(position_tex, tex_coord(position_tex, id_b), 0).xyz;
  vec3 line = pos_b - pos_a;

  float line_len = length(line);
  vec3 line_dir;
  if (line_len < 1e-4)
    line_dir = vec3(1.0, 0.0, 0.0);
  else
    line_dir = line / line_len;

  vec3 line_right_dir;
  if (abs(dot(line_dir, UP)) > 0.9) {
    line_right_dir = normalize(cross(vec3(1, 0, 0), line_dir));
  } else {
    line_right_dir = normalize(cross(line_dir, UP));
  }
  vec3 line_up_dir = normalize(cross(line_right_dir, line_dir));

  const vec2 horizontal_vertical_view_signs_array[16] = vec2[](
    vec2(1.0, 0.0),  vec2(0.0, 1.0),  vec2(0.0, 0.0),  vec2(0.0, -1.0),
    vec2(0.0, -1.0), vec2(1.0, 0.0),  vec2(0.0, 1.0),  vec2(0.0, 0.0),
    vec2(0.0, 1.0),  vec2(-1.0, 0.0), vec2(0.0, 0.0),  vec2(1.0, 0.0),
    vec2(1.0, 0.0),  vec2(0.0, 1.0),  vec2(-1.0, 0.0), vec2(0.0, 0.0)
  );

  int id = vertex_id < 4 ? id_a : id_b;
  vec3 endpoint_pos = vertex_id < 4 ? pos_a : pos_b;
  vec3 height_width_angle = texelFetch(height_width_angle_tex, tex_coord(height_width_angle_tex, id), 0).xyz;

#ifdef FIX_TWISTING
  int closer_id = (dot(camera_position - pos_a, camera_position - pos_a) < dot(camera_position - pos_b, camera_position - pos_b)) ? id_a : id_b;
  vec3 closer_pos = (closer_id == id_a) ? pos_a : pos_b;
  vec3 camera_view_dir = normalize(closer_pos - camera_position);
  vec3 closer_height_width_angle = texelFetch(height_width_angle_tex, tex_coord(height_width_angle_tex, closer_id), 0).xyz;
  vec3 diagonal_dir_border = normalize(closer_height_width_angle.x * line_up_dir + closer_height_width_angle.y * line_right_dir);
#else
  vec3 camera_view_dir = normalize(endpoint_pos - camera_position);
  vec3 diagonal_dir_border = normalize(height_width_angle.x * line_up_dir + height_width_angle.y * line_right_dir);
#endif

  bool is_vertical_view = abs(dot(camera_view_dir, line_up_dir)) / abs(dot(diagonal_dir_border, line_up_dir)) >
    abs(dot(camera_view_dir, line_right_dir)) / abs(dot(diagonal_dir_border, line_right_dir));
  vec2 signs = horizontal_vertical_view_signs_array[vertex_id + 8 * int(is_vertical_view)];

#ifndef POINTY_CAPS
  if (vertex_id == 2 || vertex_id == 7) signs = -horizontal_vertical_view_signs_array[(vertex_id - 2) + 8 * int(is_vertical_view)];
#endif

  float view_right_sign = sign(dot(-camera_view_dir, line_right_dir));
  float view_top_sign = sign(dot(-camera_view_dir, line_up_dir));
  float half_height = 0.5 * height_width_angle.x;
  float half_width = 0.5 * height_width_angle.y;
  vec3 horizontal_dir = half_width * line_right_dir;
  vec3 vertical_dir = half_height * line_up_dir;
  float horizontal_sign = signs.x * view_right_sign;
  float vertical_sign = signs.y * view_top_sign;
  vec3 pos = endpoint_pos + horizontal_sign * horizontal_dir + vertical_sign * vertical_dir;

  if (vertex_id == 2 || vertex_id == 7) {
    float line_dir_sign = (vertex_id == 2) ? -1.0 : 1.0;
    if (height_width_angle.z == 0.0) {
#ifdef POINTY_CAPS
      pos += line_dir_sign * line_dir * half_width;
#endif
    } else {
      pos += line_dir_sign * line_dir * half_width * sin(abs(height_width_angle.z) * 0.5);
      pos += sign(height_width_angle.z) * horizontal_dir * cos(abs(height_width_angle.z) * 0.5);
    }
  }

  vec3 eye_position = (view_matrix * vec4(pos, 1.0)).xyz;
  vec3 eye_normal = (view_matrix * vec4(normalize(pos - endpoint_pos), 0.0)).xyz;
  vec3 color_base = decode_color(texelFetch(color_tex, tex_coord(color_tex, id), 0).r);
  color = color_base * lighting(eye_position, eye_normal);
  gl_Position = projection_matrix * vec4(eye_position, 1.0);
}
